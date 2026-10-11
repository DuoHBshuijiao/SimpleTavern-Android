# CORE_API — 前端应调用的业务接口

2026-10-10 库交付：运行 `./gradlew assembleBackendRelease --offline`（Windows用 `gradlew.bat`），生成十个正式Release AAR和现有unsigned诊断宿主。当前构建基线为JDK17、AGP8.7.3、Kotlin2.0.21、SDK35/min26；离线构建需先有对应Gradle/SDK依赖缓存，首次缺失可沿现有仓库下载，不自动升级。

库位于 `core/{model,data,import,conversation,memory,llm,tools,api}/build/outputs/aar/<模块名>-release.aar` 和 `runtime/{sandbox,host}/build/outputs/aar/<模块名>-release.aar`。这是分别打包的模块，不是fat AAR；单个api-release.aar不含其它模块/第三方依赖，本批未配置Maven发布/POM。推荐未来前端沿当前源码子工程接入：

```kotlin
implementation(project(":core:api"))
implementation(project(":runtime:host"))
```

这两项与当前app相同，其余项目依赖沿原Gradle图带入；业务入口用 `SimpleTavernCore.get(applicationContext)`，公共suspend入口等待原进程初始化，宿主停止必须使用同一实例。保留当前app清单、通知/服务接线、回环网络配置及JNI legacy extraction/四个运行库keepDebugSymbols配置；用户授权/页面仍由后续前端负责。原默认Release不minify，未声称另设R8消费者配置也可直接使用。sandbox AAR静态包含arm64运行库/Alpine及许可证，但不是设备执行或强隔离证明。

普通离线聚合构建38秒通过，十个AAR实际存在；运行边界、当前设备/来源包缺口和产物证据见 [VERIFICATION.md](VERIFICATION.md)。不发布远端、不签名产品、不新增前端、执行恢复或测试套件。

2026-10-10 RikkaHub普通provider和模型替代连接沿原导入与 previewProvider/startGeneration/助手/预处理接口，无新Core API。已转换API-key覆盖模型的 preview.credentialId 为其模型预设ID，用原setProviderCredential配置；不借父凭据。Google Vertex IAM覆盖转用原setGoogleVertexServiceAccount(实际模型预设ID,json)，Express用API key；目录/显式缓存仍不支持Express。OpenAI generationPath只用于生成，不改模型/文件root；旧未转换对象明确再导入，未知变体不静默回落。最终普通离线编译通过42秒，见 [PROVIDERS.md#rikka-provider-override](PROVIDERS.md#rikka-provider-override)。

2026-10-10 Gemini3.8：原 startCloudSpeechPlayback/source播放在明确两种3.8 TTS时使用原合成回调交付24kHz mono PCM，完整资产仍为WAV；instructions 可用于3.8风格。没有新Core接口、自动升级来源默认模型、重复合成或会话协议；首次审批额度限制未执行，下一轮普通离线编译成功1分11秒，真实设备仍待证明，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

2026-10-10 原 startCloudSpeechPlayback/source播放入口的 MiniMax默认/明确MP3现在通过同一合成回调增量交付SSE音频，原进度/停止/整稿预处理/分段/缓存接口不变。synthesizeSpeech、无回调任务和其它格式保持原完整JSON；不新增Core接口或隐式重试。最终普通离线构建44秒通过，真实设备起声未验收，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

2026-10-10 助手选择区分：listAssistantConfigs 只返回可选配置，不把共享来源设置/记忆库列成助手；原getAssistantConfig和底层存储读取不删除或隐藏这些记录。createAssistantChat以及助手请求/预览/上下文拒绝共享容器，本地default明确创建不变，无新接口/类型表。listRecentAssistantConversations保留原时间字符串，排序同时支持带偏移和设备时区解释的来源无偏移时间。契约见 [ASSISTANT.md#assistant-chat](ASSISTANT.md#assistant-chat) 和 [CHAT.md#conversation-reference](CHAT.md#conversation-reference)。

2026-10-10 原 RikkaHub 导入入口现将助手候选 usage 写入原账本，仍用 usageEventsPage/usageSummary，不新增请求/收费接口。消息UUID在来源内去重、模型按备份引用、费用保持unknown；报告含无时区解释和 raw-only/duplicate 等计数，原三方合并保护本地修改/删除。没有原完整请求账本/费用证明，普通离线构建31秒通过，真实包未运行，见 [ACCOUNTING.md#usage-accounting](ACCOUNTING.md#usage-accounting)。

2026-10-10 助手删除：`deleteAssistantConfig(id, expectedUpdatedAt, deleteRelatedChats=true): List<String>` 在原数据 gate/Room 事务内删除配置与其独立会话，返回删除会话 ID；传 false 时有会话则冲突，绑定主聊天不删。活动会话/语音任务、仍存在的 RikkaHub 对应角色、共享来源容器返回 Conflict，不自动停止或改绑。原来源基线/历史账本和共享文件、凭据保留，见 [CHAT.md#library-removal](CHAT.md#library-removal)。

2026-10-10 预设删除：`deletePreset(id, expectedUpdatedAt)` 沿原 gate/Room 删除未被当前选择使用的预设；会话、群成员、语音/预处理、助手与来源默认占用返回 Conflict，部分占用提供 chatId/assistantId。先通过原保存接口明确改选，再删除；历史记录/凭据/源基线不删，不自动注销/停止。普通离线构建通过1分31秒，未执行用户删除，见 [CHAT.md#library-removal](CHAT.md#library-removal)。

2026-10-10 原 Desktop 整包导入新增历史 usage v1事件转换，随后由已有 usageEventsPage/usageSummary 查询，不增加收费请求/API。索引、repair和无法转换记录只保留原文；来源合并与本地保护保持，最终普通离线构建32秒通过，真实包未执行，见 [ACCOUNTING.md#usage-accounting](ACCOUNTING.md#usage-accounting)。

2026-10-10 单张角色卡：previewCharacterCardImport/commitCharacterCardImport/importCharacterCard 接显式 content URI 的 ST JSON 或 PNG tEXt 内嵌卡，沿原附件/角色/世界书事务新增独立本地记录；预览持有原PNG字节，不重开源URI、无pending框架。characterOpeningMessages 与 createChat 尾参数 openingMessageIndex=0 接候选开场白。扩展/MVU代理不自动运行，Desktop/Rikka整包合并不变；最终普通离线构建通过42秒，真实卡片/设备未验，见 [CHAT.md#character-card-import](CHAT.md#character-card-import)。

2026-10-09 资料库删除：deleteCharacter(id, expectedUpdatedAt, deleteRelatedChats=true) 沿桌面语义连带删除主聊天并返回其ID，在其它群聊仅移除成员/覆盖/MVU锚；deletePersona(id, expectedUpdatedAt) 解除当前会话选择。deleteChat 与角色连带删除共用原事务，清理消息及记忆/状态记录，并解除独立助手目标绑定；保留来源基线、共享头像/附件、历史任务和用量，已有非终态任务返回冲突。普通离线构建通过1分34秒，未删除用户数据；限制见 [CHAT.md#library-removal](CHAT.md#library-removal)。

2026-10-09 头像主线：新增 getPersona、setCharacterAvatar/setPersonaAvatar、clearCharacterAvatar/clearPersonaAvatar、getAvatarAttachment/getAvatarFile。用户 content URI 复制到原附件存储，附件与绑定同事务；取消绑定保留共享文件，读取不整图 base64。RikkaHub 图片头像仅映射备份 upload 文件，不联网或读源设备路径，沿原来源合并；普通离线构建成功56秒，真实选择器/导入未验。接口与限制见 [CHAT.md#avatars](CHAT.md#avatars)。

2026-10-09 网关状态：getSpeechGatewayHealth(SpeechGatewayHealthRequest) 只显式读取所选GLM/Qwen3/Omni网关health，Qwen可含已配置克隆网关；分项返回可达/HTTP/网络/超时/明文策略阻止，不合成或启动模型。普通离线构建成功55秒，未执行GET/设备；网络策略尚未放宽，见 [SPEECH.md#local-tts-gateways](SPEECH.md#local-tts-gateways)。

2026-10-09 GLM/Qwen3/OmniVoice 已有网关沿原 synthesizeSpeech/startSpeechTask/startCloudSpeechPlayback 接线；明确 baseUrl/voiceId，参考音频用托管 promptAttachmentId，Qwen克隆须 voiceCloneBaseUrl。不启动模型、打开桌面路径、猜主机/端口或自动重试；原用量不记免费。普通离线构建成功73秒，真实网关/设备及 LAN HTTP（仍禁止）未证明可用，见 [SPEECH.md#local-tts-gateways](SPEECH.md#local-tts-gateways)。

2026-10-09 音色试听：`startSpeechVoicePreviewPlayback(voiceTaskId, playbackSpeed=1)` 仅从成功完成的设计/克隆任务启动原 `SPEECH_PLAYBACK`。设计附件复用；远端试听 URL 每次按20,000,000字节上限下载后交设备输出，不重做设计/克隆/合成；不保证上游/CDN没有其他费用，不带 provider 凭据至 CDN，也不开放任意 URL/API。进度为 `voice_preview` 并记录创建任务、下载字节和附件 ID；保存附件可用原附件播放接口，再次显式启动远端试听会重取；进程重启不续取。暂停/停止沿原流程，真机解码未验证；GLM/SiliconFlow可能没有试听 URL。

2026-10-09 MiniMax/GLM/SiliconFlow 声音克隆从明确托管的 `sourceAttachmentId` 开始；MiniMax 可选 `promptAttachmentId` 与 `promptText` 成对提交，独立 `SPEECH_VOICE_CLONE` 复用原设计 worker、宿主和停止流程。结果提供 `voiceId`、`voiceType` 以及可得的 `remotePreviewUrl`、`uploadedFiles` 和局部 `requestCount`，不自动播放、下载 CDN、保存预设、选择声线、删除云文件或音色，也不重发。独立 `speech_voice_clone` 账本记录未知费用和输入字数，不混入 TTS/LLM/设计账本；旧设计别名及默认字段兼容，native/restart 仅中断。三个 Luna 实际完成 DTO、展示和两个 provider 适配，主代理整合 HTTP/任务；GLM 桌面契约未验证，MiniMax/SiliconFlow 官方字段已核对；本批最终普通离线构建 `session45536` 成功（33s，230 tasks：6 executed、224 up-to-date），仅有编译/静态打包证据；真实账号/音频/设备未验证且其他后端仍有余项，无新增表/依赖/前端/hash/冒烟/单测。

2026-10-09 MiniMax 声音设计：新增 `startSpeechVoiceDesign`、`getSpeechVoiceDesignProgress`、`awaitSpeechVoiceDesign`、`observeSpeechVoiceDesign`，作为独立 `SPEECH_VOICE_DESIGN` 任务接原通知、停止和宿主生命周期。请求沿用来源/凭据选择，包含 prompt、最多 500 字符的 `previewText` 和可选 `voiceId`，同源请求 `/v1/voice_design`；结果含 `voiceId` 和可选托管 MP3 `previewAttachment`。用户可显式调用 `startChatAttachmentPlayback` 试听；不会自动保存预设、选择声线、播放或删除远端声音，独立 `speech_voice_design` 账本记录次数和输入字数、费用未知，不计作 TTS 或 LLM。重启恢复为 `interrupted` 且不重发；两个 Luna 实际编写 DTO、账本显示与 HTTP，主代理接 worker。最终当前源码普通离线构建成功：`BUILD SUCCESSFUL in 26s`，exit 0，230 tasks（10 executed、220 up-to-date）；`core:tools:compileDebugKotlin` 实际重编并包含最后的 `voiceId` Unicode 校验，日志无 `e:`/`w:`/`FAILED`，SDK XML 提示非致命。runtime JAR 与 APK 中可见新类标记（静态证据，非运行验证）；未验证真实账号/设备，参考音频克隆及其他后端余项继续，无新表、依赖、前端、哈希、冒烟或单测。

2026-10-09 ElevenLabs 语音目录：原 `getSpeechDirectory` 的 `fetchVoices` 请求 `GET /v2/voices`，使用 `page_size=100`、`include_total_count=false`，按 `has_more` 与 `next_page_token` 最多续 100 页、累计最多 10,000 条；首页允许超过 100 条。每页沿原 HTTP 响应 8 MiB 限制，认证头使用 `xi-api-key`，不使用 Bearer。`fetchModels` 请求 `GET /v1/models`，解析根数组；`can_do_text_to_speech` 的 true/false/null 分别映射为 `provider_reported_speech`/`not_tts`/`unverified`，不推测能力。配置目录与远端目录并存且不保存；不自动选择 voice/model、不调价、不合成、克隆或下载试听。非 ElevenLabs 的旧 object/Bearer 路径不变；Room 事务结束后才联网，取消与安全错误沿用原处理。本批普通离线 `assembleDebug` 已完成：`BUILD SUCCESSFUL in 28s`，230 tasks（10 executed、220 up-to-date），exit 0；日志时间 2026-10-09 21:29:49，APK 15,319,988 B（21:29:48）。日志无 `^e:`/`^w:`/`FAILED`；SDK XML 非致命提示仍存在。未验证真实账户、API 或设备。无新依赖、表、任务、hash 或测试。

2026-10-09 末尾链接对象删除/重命名：仅末尾名称不跟随，祖先链接仍按 guest root 展开。delete_file 以 lstat 确认普通文件或符号链接（包括悬空/循环链接），再用原 File.delete() 删除该名称对象，不触碰链接目标；目录不删除。rename_file 检查源与目标名称，目标已存在（含悬空链接）即冲突，保留不覆盖契约。approval premise 记录链接对象的 lstat/readlink 与 manifest，沿用原工具及审批类别；不新增 Core 链接创建/读取入口。普通离线编译/打包通过57秒（230 tasks：17 executed、213 up-to-date）；未做真实用户文件/设备操作，不代表全部后端完成或通用 POSIX 语义、TOCTOU 防护、隔离完成。

2026-10-09 长稿预处理：原 `startCloudSpeechPlayback` 不改接口，改为整稿预处理一次、再按最终稿拆段；单次合成/独立预处理不变。SpeechPlaybackProgress新增默认null的 preprocessingRequestId；云端 segmentCount=0 表示尚未确定最终分段。所有子合成沿原任务/播放/缓存和独立字符账目，共享父预处理事件；不改来源合并、消息正文或引入后台框架。普通离线 `:app:assembleDebug` 编译/打包通过，耗时77秒；真实云端、设备及完整 native 验证仍缺，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

2026-10-09 技能管理与导出：模型新增 `skill_link_asset`、`skill_delete_file`、`skill_delete`，沿原审批、旧值复核和 Core link/delete 方法，不新增删除实现或审批类别。新增 `exportAssistantSkill(assistantId, directory, destination: Uri): Uri`：短 Room 事务取得定义/文本/资产引用，事务结束后仅持共享 data gate 生成完整 ZIP，释放 gate 再写已授权 Uri并只清本次缓存。保留单目录及资源相对路径，512 文件/256 MiB 展开/128 MiB 压缩上限与安装一致；固定 builtin skill-creator 在缺 LICENSE.txt 时附原许可证。导出不带 builtin/启用元数据，重装按普通包和既有同名并存规则处理。普通离线编译/打包通过54秒；未作真实 SAF/设备验证，见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

2026-10-09 沙箱创建/初始化：`createSandbox` 的运行时发布先在 staging 预写 marker、检查碰撞并最后发布 marker；普通发布错误逆序撤回，遇首个撤回失败即停，保留 staging 并报告仍留在 root 的发布项，避免 marker 撤回失败后继续撤回 base。创建登记成功后取消保留根，登记前失败清理本次根。进程终止/断电、文件系统持续失败与实际设备验证仍缺，不提供崩溃恢复，见 [SANDBOX.md#sandbox-runtime](SANDBOX.md#sandbox-runtime)。

2026-10-09 guest 链接路径（历史表述）：当时记录删除/重命名保留原解析目标语义；该行为已由本文件顶部末尾链接对象记录更新。其余路径解析、复制目的地限制、native v1/schema 2 metadata 与验证缺口见 [SANDBOX.md#sandbox-runtime](SANDBOX.md#sandbox-runtime)。

2026-10-09 模型目录与价格：新增 refreshModelCatalog() 明确取得 models.dev/OpenRouter 公共完整目录，原getModelCatalog/getModelCapabilities读取最近可用快照，缓存缺失仍用bundled；新增previewModelPricingCatalog(List<ModelCatalogPriceSelection>)及installModelPricingCatalog(selections,expected)沿原价格库安装选定标量报价，保留用户价和历史冻结费用。明确实际usageProvider，不自动换模型/主机/登录或安装所有价格；阶梯/独立模态等额外维度仍未转换，见 [PROVIDERS.md#model-catalog-refresh](PROVIDERS.md#model-catalog-refresh)。

2026-10-09 Android ZIP预检：新增 `Core.preflightImport(archive: Uri): PreflightResult`，原File入口保留。`ImportTaskService.preflight(uri)` 复用shared data lease及 `SourceArchiveInputs.open/copyTo`，将SAF片段/声明长度/管道输入仅复制一次到随机 `source-preflight-*/archive.zip`，由ZipFile随机读取；处理8 GiB输入上限、条目预算、协程取消及finally关闭描述符/仅清理本次缓存。仅返回source/entries/notes，不是任务或导入计划；不合并/解压用户文件、不读RikkaDB/写映射、不取持久授权、不算hash。取消仅取消预检，不调用stopTask或消费importer取消标记。进程被杀可能留下可由系统回收的应用缓存，不续跑/恢复；普通离线构建成功55秒，但真实SAF/管道/账号/设备未验收，见 [VERIFICATION.md](VERIFICATION.md)。

2026-10-09 在线模型目录收尾：`listProviderModels(presetId,pageToken=null,pageSize=100,kind=MODELS):ProviderModelPage` 增加 Vertex IAM service-account publisher models 与 Bedrock SigV4 foundation models；Bedrock inference profiles 由显式 `BEDROCK_INFERENCE_PROFILES` 选择。Vertex 固定 publisher `v1beta1`，Bedrock 保留 typed credential 并沿 SigV4 canonical query 签名；响应追加可得资源/状态/模态/推理类型/流式/profile 元数据。Codex/Express/其它 publisher、部署与预置资源、完整 catalog/价格及真实权限/设备仍缺；不改 preset/selectedModel/离线目录/价格/usage。无新表/依赖/自动刷新/hash/冒烟/单测/前端，见 [PROVIDERS.md#model-directory](PROVIDERS.md#model-directory)。

2026-10-09 在线模型：新增 listProviderModels(presetId,pageToken=null,pageSize=100):ProviderModelPage，明确读取所选加密连接的模型列表；支持标准 OpenAI/Responses、Claude、Gemini 与原已登录 Copilot 路由，不需要新建聊天/配置生成模型。离线目录、预设及价格不变；Claude/Gemini逐页，OpenAI无分页返回全列表。可取消、原shared lease及无重发HTTP，无新任务/表/依赖/前端/文件校验/冒烟/单测；原生云目录与真实权限/设备仍缺，见 [PROVIDERS.md#model-directory](PROVIDERS.md#model-directory)。

2026-10-09 AWS鉴权：新增setBedrockAwsCredentials(credentialId,accessKeyId,secretAccessKey,sessionToken=null):String，返回实际aws:bedrock:加密引用；preset明确authStyle=aws_sigv4绑定该引用。has/removeProviderCredential共用原入口，普通setter/头/其它API-key不能读取该结构化记录。聊天/维护/明确TTS预处理已接SigV4，不申请STS或自动刷新，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09 Converse后续：沿原生成params支持promptCache explicit(system/tools/history_tail、5m/1h)/implicit、thinking或thinking_budget、output_config.effort、top_k、service_tier，以及response_format的json_schema；function.strict沿原工具定义传入。仍无新Core缓存管理接口，断点随本次请求发送；不创建客户端索引或用哈希判断命中，详情和兼容边界见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09 Bedrock：沿原 preset 配置与加密密钥、previewProvider 和生成/维护/明确 TTS 预处理接口，无新 Core 门面。`amazon-bedrock` + `providerParams.region` 默认 native Anthropic InvokeModel；明确 `protocol: bedrock_converse` 才选择 Converse。原 `stream: false` 可选择同步请求，仍用原任务/停止/工具循环；Mantle 兼容项不变。API-key/Bearer 范围、附件与未完成项见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09 Vertex缓存：沿原startGeminiCacheCreate/List/Operation、明确cleanup与setGeminiCacheForGeneration入口支持服务账号原生连接，来源promptCache explicit/history-tail也接原生成链；没有新接口。操作仍按配置项目/区域，短期token刷新保留同账号/key绑定。GCS/大文件、CMEK、OpenAI兼容缓存及跨进程前缀仍未接，见 [GEMINI_CACHE.md#vertex-cache](GEMINI_CACHE.md#vertex-cache)。

2026-10-09 Vertex：新增configureGoogleVertexPreset(presetId,GoogleVertexOptions,expectedUpdatedAt)和setGoogleVertexServiceAccount(presetId,serviceAccountJson)，私钥只存现有加密库。通用get/refresh/logoutProviderOAuth已路由Vertex；聊天/维护/TTS预处理接短期Bearer token与项目/区域的Google固定路由，Express API-key模式保留。离线准备不兑换token，没有生成失败重发；尚未接ADC/WIF、GCS、Vertex专用Files/cache或Bedrock，见 [PROVIDERS.md#provider-oauth](PROVIDERS.md#provider-oauth)。

2026-10-09 URI备份/预检：startBackup(destination:Uri, options=BackupOptions(),password=null)返回BACKUP任务；exportBackup相同参数等待成功返回Uri；preflightNativeBackup(archive:Uri)返回NativeBackupPreflight。原File入口不变。BackupTaskProgress追加outputUri/documentBytes/documentMayBePartial/documentWriteCompleted/privateArchiveRetained默认值，ZIP先完整生成再写文档，wt替换选定目标，停止/失败可留partial文档但不自动删除/重放；预检是结构只读操作，不是完整恢复计划。见 [NATIVE_BACKUP_V1.md](NATIVE_BACKUP_V1.md)。

2026-10-09 URI恢复：inspectNativeRestore/startNativeRestore/restoreNativeBackup新增archive:Uri重载，原File入口不变；使用已授权content输入和AssetFileDescriptor片段/管道，直接进原私有restore/inspection暂存及原提交/回滚，不多做输入快照或重放。NativeRestoreProgress追加inputBytes默认0，preflightNativeBackup仍仅File，详见 [NATIVE_BACKUP_V1.md](NATIVE_BACKUP_V1.md)。

2026-10-09 SAF导出：新增startExportSandboxFileToDocument(sandboxId,sourcePath,destination:Uri)，返回原FILE_EXPORT任务并共用进度/宿主/停止。SandboxExportDestination追加DOCUMENT，进度尾部追加documentUri/documentMayBePartial/documentWriteCompleted默认值，旧托管导出不变。只接受已有写授权的content URI，wt替换所选目标，64 KiB复制/8 GiB上限，失败可留下部分文档；不自动取权限、删文件、回滚或重放，详见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。

2026-10-09 沙箱后台导出：新增startExportSandboxTextToAssistantWorkspace、startExportSandboxFileToAssistantWorkspace、startExportSandboxFileToChatAttachment返回FILE_EXPORT TaskSnapshot，以及getSandboxExportProgress/observeSandboxExportProgress。原同步导出接口保留；新任务共用宿主/stopTask/中断、原读取/附件与工作区发布，同事务记录目标及任务成功，不重放。文本256 KiB、工作区资产8 GiB、聊天附件256 MiB；结果不自动发给模型，详见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。

2026-10-09 播放列表：新增startSpeechPlaylist(List<SpeechPlaylistItem>, playbackSpeed=1)、skipSpeechPlaybackTo(taskId,itemIndex,positionMs=0)、nextSpeechPlaybackItem、previousSpeechPlaybackItem。source=CACHED/ATTACHMENT只引用托管ID，复用原SPEECH_PLAYBACK/观察/等待/暂停/seek/stop，不合成或收费；skip等待目标输出就绪及初始定位。SpeechPlaybackProgress增playlist/completedPlaylistIndices默认空，segmentIndex/Count表示当前项/总项；SpeechCacheClearResult增retainedAssets，播放列表引用的缓存暂不清除。系统媒体动作及范围限制见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-09 MCP OAuth：McpServerConfiguration增可选McpOAuthOptions公开配置；新增discoverAssistantMcpOAuth、startAssistantMcpOAuth(assistantId, serverId, scopes: List<String>?=null)、completeAssistantMcpOAuth(loginId, callbackUrl)、cancelAssistantMcpOAuth、getAssistantMcpOAuthStatus、refreshAssistantMcpOAuth、logoutAssistantMcpOAuth。返回McpOAuthDiscovery/McpBrowserLogin/McpOAuthStatus，不含token/code/verifier；登录/注册仅明确开始时联网，新连接按需刷新，本轮工具不重放。浏览器完整回调由宿主提交，关闭OAuth后仍可清本地记录，public-client范围与限制见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-09 MCP资源模板：新增suspend expandMcpResourceTemplate(uriTemplate, variables: JsonObject)纯展开，以及readAssistantMcpResourceTemplate(assistantId, serverId, uriTemplate, variables)沿原resources/read取得结果。支持各运算符、前缀、explode与标量/列表/对象，UTF-8编码；不下载链接、打开文件、自动补参数或新建任务，见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-09 MCP通知：新增 `observeAssistantMcpNotifications(assistantId, serverId, resourceUris=emptyList()): Flow<McpNotificationEvent>`，创建 Flow 不联网，collect 时才连接并订阅明确 URI；只发布四类变更提示，不自动读取资源、执行工具或写聊天。普通离线编译/打包通过67秒（230 tasks：23 executed、207 up-to-date，exit 0）；无真实服务器/设备验证。接口边界见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-08 MCP整组提示词：新增applyAssistantMcpPrompt(chatId, draft: McpPromptResult, expectedUpdatedAt, characterId=null)，返回McpPromptApplication的节点/候选/附件ID及chatUpdatedAt。按user/assistant原顺序和媒体追加历史，同事务发布，现有生成/聊天版本冲突先拒绝；不联网、自动生成或升为system/tool。草稿增sourceNamespace固定取得时来源，普通/群成员/独立助手映射范围见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-08 内置/编辑技能：SkillDefinition增builtin默认false，内置文件只读，同名用户技能优先；现有Core管理接口不换。明确RikkaHub导入离线补固定版唯一skill-creator，不自动启用或修改Desktop/local；模型skill_list/skill_files/skill_read_file/skill_write_file/skill_set_enabled接原审批/工具保存，文本编辑比较已读原文与updatedAt，不新增任务/表。来源原文与许可证保留，读取提供实际平台说明，见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

2026-10-08 技能模型准备：公开复制 API 不变，新增模型 stage_skill(name, directory?)，复用 copyAssistantSkillToSandbox 的原复制实现与 FILE_COPY 子任务。普通来源/群聊需所选沙箱，独立助手另需 sandboxToolsEnabled；归 WRITE，返回 guestPath/完成状态/子任务 ID，不执行脚本或额外开放 shell。use_skill 保持 READ，见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

2026-10-08 媒体会话：Core API不换，CoreForegroundService把当前播放任务及AudioPlaybackState接原生MediaSession/MediaStyle；锁屏/耳机回调沿pause/resume/seek/stopTask，动作按能力开放且绑定原ID，不提供重启重播或媒体浏览。系统通知新增暂停/继续和停止、仅通用元数据，不新增应用页面/依赖/表，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-08 音频控制：新增pauseSpeechPlayback/resumeSpeechPlayback/seekSpeechPlayback(taskId, positionMs)，返回原SpeechPlaybackProgress；新增playback运行态含ready/paused/canPause/canSeek、位置和时长。编码/PCM原输出可暂停，本地文件/缓存可定位，云端增量流不seek，系统TTS明确不支持；分段暂停意图保持，不重建任务或重新合成，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-08 手动Gemini缓存生成：新增setGeminiCacheForGeneration(chatId, selection: GeminiCacheGenerationSelection?, characterId=null)与getGeminiCacheForGeneration。选择本进程CREATE的原任务ID，MATCH_PREFIX替代已匹配历史、PREPEND明确增加缓存前置上下文；null清除但不删除。选择优先自动新建，失效/冲突不降级重发；只进程内、按chat/实际成员隔离，原任务/lease/用量链不换，见 [GEMINI_CACHE.md#gemini-cache-generation](GEMINI_CACHE.md#gemini-cache-generation)。

2026-10-08 技能整目录复制：新增 copyAssistantSkillToSandbox(assistantId, directory, sandboxId, targetDirectory=null)，返回 SkillSandboxCopyResult 的已开始文件/原FILE_COPY进度、整体完成与失败路径。默认新目录，保留相对文件名，不运行脚本；FileCopyProgress新增exactTarget默认false，整目录子任务设true，冲突失败不随机改名。协程取消停止当前文件、保留已发布部分，不新增批次任务或重启重放，见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

2026-10-08 独立技能安装：新增 installAssistantSkill(assistantId, archive: File/Uri, options=SkillInstallOptions()) 和 installAssistantSkillFromGitHub(assistantId, GitHubSkillSource, options=...)。支持 ZIP 单技能自动选择、多技能 skillPath、显式 directory、目录冲突自动并存；返回 SkillInstallResult。不改启用列表、不执行脚本、没有新任务ID；调用方协程负责取消。接口、同名启用及来源隔离见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

2026-10-08 MCP stdio：原配置/发现/生成及资源提示词入口不换。McpServerConfiguration增command/args/environment/credentialEnv/sandboxId，stdio的url可空；新增setAssistantMcpEnvironmentCredential(assistantId, serverId, name, value?)保存来源隔离的加密环境凭据，不返回值。需明确选择已有Linux沙箱和可运行命令，不自动安装依赖或执行桌面程序。FileObservationReport.taskId可空以表示非通用任务观察，stdio生命周期与限制见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-08 手动缓存附件：startGeminiCacheCreate签名不变，GeminiCacheCreateRequest.contents中的ContextMessage.attachments支持托管附件ID引用，小文件内联/大文件上传；调用方media不作为文件来源。GeminiCacheTaskProgress新增fileTransfers及phase=preparing，sourceFilesExpireTime在POST前首次确认，仍沿原观察/停止/任务与通用账目；创建缓存不自动请求生成，见 [PROVIDERS.md#provider-files](PROVIDERS.md#provider-files)。

2026-10-08 上传复用/缓存：各生成入口签名不变，同一次生成、同一群聊成员的工具续轮共用LlmFileSession；GenerationMetadata.fileTransfers增reused/reuseCount及cache_retained/retainedUntil。自动Gemini historyTail已支持上传文件，GeminiCacheTaskProgress增sourceFilesExpireTime，续期不得越过源文件到期。原LlmClient.stream仍自动管理单请求session，远端句柄只在内存，跨生成尚未免上传；缓存源文件保留至上游到期而非在生成结束删除，见 [PROVIDERS.md#provider-files](PROVIDERS.md#provider-files)。

2026-10-08 大音频播放：startChatAttachmentPlayback签名不变，直接解析托管文件并按需读取，上限256 MiB，不再受getChatAttachmentContent的6 MiB内联限制；PCM有报告的采样率/声道分别与明确选择核对。原停止、速度、焦点、进度与恢复不重播链保留，没有新增公开路径/URL、表或任务引擎，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-08 大文件：原importChatAttachment(File/Uri/bytes)复制上限256 MiB；各生成入口attachmentIds不变，大文件及列明Office按实际协议临时上传。ContextMedia新增请求内attachmentId/uploadFile/providerFile，内容不序列化，getChatAttachmentContent不公开磁盘或远端句柄、仍原有界内联。GenerationMetadata.fileTransfers返回上传/处理/释放状态，不含远端句柄，沿原候选/最终Done.metadata观察。具体类型、预算和缓存边界见 [PROVIDERS.md#provider-files](PROVIDERS.md#provider-files)。

2026-10-08 生成音频：原生成入口接Chat modalities/audio、Gemini responseModalities.AUDIO/speechConfig/responseFormat.audio；音频保存到assistant候选attachments，ContextMedia增sourceMimeType用于布局参数。新增startChatAttachmentPlayback(attachmentId, pcmFormat?, playbackSpeed)，沿原SPEECH_PLAYBACK/停止/观察，进度增attachmentId/mode=attachment；原getChatAttachmentContent可读字节。原始PCM需已报告或明确实际布局，未自动播放，边界见 [CHAT.md#generated-audio](CHAT.md#generated-audio)。

2026-10-08 模型图片：原生成入口接Gemini responseModalities/imageConfig、Responses image_generation；完整图片保存到assistant候选images，ProviderTurn新增mediaAttachmentIds并沿原历史还原原生数据。新增getChatAttachmentContent(id): ContextMedia，复用有界托管读取（图片base64/文本）；Done.media只请求内、不序列化或作为前端下载入口。接口和余项见 [CHAT.md#generated-media](CHAT.md#generated-media)。

2026-10-08 MCP资源/提示词：新增 getAssistantMcpCapabilities/listAssistantMcpResources/listAssistantMcpResourceTemplates/readAssistantMcpResource/listAssistantMcpPrompts/getAssistantMcpPrompt/completeAssistantMcpArgument，明确指定当前选中 serverId，列表在同一连接完成分页。资源/提示词返回 typed 描述及请求内媒体草稿，不自动加入历史或执行；新增 importChatAttachment(chatId, bytes, filename, mimeType) 支持明确复制选择的字节，沿原附件/生成链。通知收集现已部分接入，长期共享连接等余项见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-08 工具媒体：ToolResult增请求内media，聊天执行沿原链自动托管到tool消息；AttachmentRef增modelVisible=true，MCP媒体仅user可见提示可置false，消息/工具记录有附件索引和元数据。生成/观察/历史接口不换，未知类型只保存不伪装可识别，协议范围及余项见 [ASSISTANT.md#tool-media](ASSISTANT.md#tool-media)。

2026-10-08 音视频输入：原 `importChatAttachment` 与各生成入口的 `attachmentIds` 现支持Chat Completions的WAV/MP3、Gemini内联音频/视频；普通/群聊/独立助手与历史/工具续轮共用。其他协议未支持类型明确失败，文件预算不扩大、不自动上传/转写/换模型，见 [CHAT.md#multimodal-input](CHAT.md#multimodal-input)。

2026-10-08 长稿/流式：`startCloudSpeechPlayback` 在原10000码点/40000字节范围内按提供方限额拆段并沿原子任务边收边播；`SpeechPlaybackProgress` 增 `segmentCount`/零起始 `segmentIndex`/`synthesisTaskIds`/`assetIds`，旧单项字段表示当前段。直接合成仍单次，MiniMax/Gemini JSON仍收完，预处理仍逐段，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。旧批“SSE仅收集”由此更新。

2026-10-08 新六家语音：SpeechRequest.providerOptions 表达本适配的提供方选项，Qwen/xAI/Fish Audio/Step/MiMo/Volcengine 接原语音入口。xAI model=xai-tts 只是无模型选择接口的内部描述，火山 model=实际资源 ID，Fish voiceId 可为空表示不指定；公开原始 PCM 播放仍需布局。SSE 收集并非边收边播，范围与来源转换见 [SPEECH.md#speech-source-adapters](SPEECH.md#speech-source-adapters)。

2026-10-08 来源云端语音：SpeechRequest 增 instructions/stability/similarityBoost，OpenAI/Groq/Gemini/ElevenLabs 接原语音 API；startSystemSpeech/startSpeechPlayback/startCloudSpeechPlayback 增可选 playbackSpeed=1（0.25..4），进度保存请求速度，source defaultTTSPlaybackSpeed 已应用。接口/格式/未支持项见 [SPEECH.md#speech-source-adapters](SPEECH.md#speech-source-adapters)。

2026-10-08 播放：新增 startSystemSpeech(SystemSpeechRequest)、startSpeechPlayback(assetId, pcmFormat?)、startCloudSpeechPlayback(SpeechRequest, pcmFormat?)、getSpeechPlaybackProgress/awaitSpeechPlayback/observeSpeechPlayback；原 stopTask/任务通知停止有效，task kind=SPEECH_PLAYBACK。SystemSpeechRequest 不默认日志输出正文；原始 PCM 需明确实际 PcmPlaybackFormat。来源 text_to_speech 返回独立播放 ID，合成与播放成功分开，详见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-08 设备工具：新增 getDeviceToolPermissions，返回日历读写/Usage access/开机解锁状态与用户可明确发起的系统设置 action，不自动申请权限。来源 calendar/screen_time 安装 calendar_query/calendar_create/get_screen_time，创建需逐项确认，沿原工具审批/等待/结果/停止/续轮；参数和局限见 [ASSISTANT.md#device-tools](ASSISTANT.md#device-tools)。

2026-10-08 用户问题：新增 observePendingApprovals/getUserQuestion/submitUserAnswers；UserQuestionRequest/UserQuestionAnswer 表达文字、单选、多选及实际输入，ask_user 不能用 approve 代替回答，可用原 reject/stopTask 拒绝或停止。原 FIFO/任务状态复用，无前端修改，见 [ASSISTANT.md#user-questions](ASSISTANT.md#user-questions)。

2026-10-08 剪贴板：localTools 选中 clipboard 后安装 clipboard_tool(action, text?)；纯文本读取与写入实际接 Android 服务，动态归 READ/WRITE，按原审批/结果链处理。后台不可读不假报成功，无新增权限/直接执行 API，见 [ASSISTANT.md#assistant-local-tools](ASSISTANT.md#assistant-local-tools)。

2026-10-08 技能：新增 listAssistantSkills/listAssistantSkillFiles/readAssistantSkillFile/writeAssistantSkillFile/linkAssistantSkillAsset/deleteAssistantSkillFile/deleteAssistantSkill/setAssistantEnabledSkills/startCopyAssistantSkillFileToSandbox。SkillDefinition/SkillFile 为 typed 元数据和文件快照，编辑/删除使用读得快照，启用使用配置 updatedAt；共享 scope、use_skill 和未完成范围见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

2026-10-08 MCP：新增 getAssistantMcpConfiguration/getAssistantMcpServers/configureAssistantMcpServers/setAssistantMcpHeaderCredential/listAssistantMcpTools。读写配置不连接网络，明确发现或生成才连接；工具调用复用原审批/取消/续轮。DTO 不含认证值；通知收集已部分接入，长期共享连接及其他边界见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-08 内置工具：AssistantConfig.settingsJson.localTools 中 javascript_engine/time_info 已接普通/群聊当前角色/独立助手的原生成工具链，不增加额外执行 API。JavaScript 用 Rhino 适配现有构建，具体字段、限制与未支持项见 [ASSISTANT.md#assistant-local-tools](ASSISTANT.md#assistant-local-tools)。

2026-10-08 产物导出：新增 exportSandboxTextToAssistantWorkspace/exportSandboxFileToAssistantWorkspace/exportSandboxFileToChatAttachment，提供独立副本或托管附件；新增 linkAssistantWorkspaceAsset/deleteAssistantWorkspaceAsset，替换以旧 attachmentId 比较，删除只移除引用不删共享字节。是可取消的 suspend 操作，不是独立导出后台任务，大小/生命周期见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。

2026-10-08 非流式：ProviderConfig.streaming 默认 true，ProviderSelection 预览增加同名状态。会话 paramsJson 的 stream=false 可选择普通 JSON 生成，沿用原任务、观察、保存、停止与工具续轮接口；Desktop streamEnabled/RikkaHub streamOutput 转控制而不只保留。协议范围和专用 Codex 限制见 [PROVIDERS.md#non-streaming](PROVIDERS.md#non-streaming)。

2026-10-08 文件使用链路：新增 startCopyAssistantWorkspaceFileToSandbox/startCopyAssistantWorkspaceAssetToSandbox/startCopyAttachmentToSandbox，用户明确选择工作区文本、资产或附件后复制到选定沙箱。返回原 FILE_COPY 任务，用原 progress/stopTask/文件变更接口；不覆盖现有目标、不挂载/同步/自动执行，见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。

2026-10-08 历史引用：新增 listRecentAssistantConversations/searchAssistantConversations/readAssistantConversation，返回近期标题、匹配片段及分页选中原文。RikkaHub 普通/独立助手的 enableRecentChatsReference 已接原工具续轮；不自动注入或执行其他聊天。查询/来源范围见 [CHAT.md#conversation-reference](CHAT.md#conversation-reference)。

2026-10-08 共享记忆：新增 getAssistantMemoryPool/listAssistantMemories/getAssistantMemory/createAssistantMemory/updateAssistantMemory/deleteAssistantMemory，提供个人或同来源全局池逐条读写；写入使用 pool，编辑/删除使用 expectedContent 原文。普通角色及独立助手记忆工具已接原审批/续轮，详见 [ASSISTANT.md#assistant-memory](ASSISTANT.md#assistant-memory)。

2026-10-08 正则：previewContentRegex(chatId, text, role=assistant) 增可选角色；新增 previewMessageContentRegex(chatId, messageId)，返回 MessageContentProjection 的正文/显示正文、思考/显示思考和 errors，原候选不改。ContentRegexRule 增 engine/roles/visualOnly/applyToReasoning/scanAllHistory/skipOpeningMessage，原编辑/助手工具支持；旧默认保留 Desktop 行为，Kotlin 引擎仅 replace/global。pageMessages 与增量事件仍为原始数据，预览不含历史锚点/深度限制，具体范围见 [CHAT.md#content-regex](CHAT.md#content-regex)。

2026-10-07 创建：createChat 签名不变，自动沿角色来源选择默认连接/配置，并在同一事务复制 RikkaHub 预设消息；返回复制消息后的 updatedAt。ChatOverrides 新增 messageTemplate:String?=null，可用现有 saveChat 显式修改。独立助手 null 继承配置模板，明确创建时也复制预设消息，但不继承普通聊天的叙事状态或额外工具权限。旧 RikkaHub 配置缺 chatDefaults 时需明确再次导入，不静默使用本地配置，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 会话：ChatOverrides新增sessionSystemPromptEnabled=true、worldBookSelectionMode=merge（另有character/session）、contextMessageLimit=null（普通不限，助手继承配置，显式0关闭）；现有saveChat可改。新增setWorldBookSelectionMode(chatId, mode, expectedUpdatedAt)，助手共享实现；buildContext/生成读取全部选中历史、节点锚点和阶梯条数裁剪，无80/200隐藏窗口或Room schema变化，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 世界书：现有getWorldBook/saveWorldBook的WorldBookEntry新增scanDepth/constantActive/priority/position/injectDepth/role，entriesJson兼容旧默认、不新增Room schema。助手条目add/update同步支持这些字段；导入按条目ID逐字段三方合并，不改来源并存。扫描继承/空窗口、五种位置、编辑范围与未补行为见 [CHAT.md#worldbook-injections](CHAT.md#worldbook-injections)。

2026-10-07 聊天上下文：buildContext(chatId, characterIdOverride=null) 支持出场角色预览，正文计算移到Default dispatcher。普通生成同一builder接示例对话、来源全局system/prefill、占位符只读渲染、实际群聊system深度、directive MVU和KG全部位置、context_size=0关闭估算裁剪；新增ChatOverrides.knowledgeGraphBeforeLastRole及override_global模式，无Room schema变化。签名/覆盖与线上位置限制见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 工作区：新增 listAssistantWorkspaceFiles/listAssistantWorkspaceAssets、readAssistantWorkspaceFile/getAssistantWorkspaceAsset、writeAssistantWorkspaceFile/deleteAssistantWorkspaceFile、beginWorkspaceCharacterCard/patchWorkspaceCharacterCard/replaceWorkspaceCharacterCard/publishWorkspaceCharacterCard。按 assistantId 隔离，文本前提 expectedContent 用已读完整正文，null 仅新建；发布更新目标另需 expectedUpdatedAt。草稿不自动进库、不操作沙箱文件。签名/字段/导入和备份限制见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。

2026-10-07 内容主线：新增 getWorldBook(id)、deleteWorldBook(id, expectedUpdatedAt)、activeWorldBooks(chatId)；setWorldBookGlobalExclusion(chatId, worldBookId, excluded, expectedUpdatedAt)、attachWorldBook/updateWorldBookAttachment(chatId, WorldBookAttachment, expectedUpdatedAt)、detachWorldBook(chatId, worldBookId, expectedUpdatedAt)、reorderWorldBookAttachments(chatId, orderedIds, expectedUpdatedAt)。写入走原 writeData/Room，返回新的 ChatSession；先读取 getChat 的 updatedAt，冲突重新读取。删除同事务清理角色与聊天 typed 引用但保留来源合并基线。助手共享这些实现并走逐项审批；新增正文规则与资料库卡正文工具、世界书实际上下文语义/限制见 [ASSISTANT.md#assistant-chat](ASSISTANT.md#assistant-chat)。工作区草稿没有冒充已接。

2026-10-07 Codex：原设备登录/轮询/取消/状态/刷新/退出同时识别 Copilot/Codex；新增 `startProviderBrowserLogin(presetId)` 和 `completeProviderBrowserLogin(loginId, callbackUrl)`，仅 Codex 完整回调 URL。ProviderDeviceLogin 增 method，ProviderOAuthStatus 增 accountId；loginId 视为不透明值，不解析前缀。语音预处理准备/预览只读，实际执行才按需刷新。兼容流程/参数限制/官方注册区别见 [PROVIDERS.md#codex-oauth](PROVIDERS.md#codex-oauth)。

2026-10-07 鉴权主线：新增 `startProviderDeviceLogin(presetId)`、`pollProviderDeviceLogin(loginId)`、`cancelProviderDeviceLogin(loginId)`、`getProviderOAuthStatus(presetId)`、`refreshProviderOAuth(presetId)`、`logoutProviderOAuth(presetId)`。当前仅 GitHub.com Copilot；DTO 不含设备/访问 token；预览/状态离线，明确轮询/刷新才联网，新生成可按需刷新。`oauth:` 凭据前缀保留，普通 API-key 接口不可修改。使用流程/取消语义/兼容与剩余项见 [PROVIDERS.md#provider-oauth](PROVIDERS.md#provider-oauth)。

2026-10-07 助手主线：新增 `createAssistantChat(AssistantChatBinding, title, sandboxId)`、`listAssistantChats(assistantId?)`、`startAssistantGeneration(chatId, userText, provider?, webSearch, attachmentIds)`；原消息分页/候选/分叉/附件/observeGeneration/stopTask/审批接口共用。previewProvider/buildContext 识别助手模式。绑定、开关、已实现工具和历史迁移限制见 [ASSISTANT.md#assistant-chat](ASSISTANT.md#assistant-chat)。

2026-10-06 主线沙箱：`createSandbox(name)` 已包含离线安装 arm64 Linux base；新增 IO 查询 `getSandboxRuntimeStatus(id)` 与明确写入 `initializeSandboxRuntime(id)`，旧环境不自动覆盖/安装。现有 shell 任务/工具入口改用 PRoot guest，无 host-shell fallback。版本/兼容/并行和未验证边界见 [SANDBOX.md#sandbox-runtime](SANDBOX.md#sandbox-runtime)。

2026-10-06 主线新增 `importChatAttachment` 的 File/content URI 重载；单聊/群聊全部 `startGeneration/startGroupGeneration` 重载新增末尾可选 `attachmentIds=[]`，支持纯附件用户消息、历史附件与四协议图片/PDF/UTF-8 文本。实际模型能力与当前输入限制见 [CHAT.md#multimodal-input](CHAT.md#multimodal-input)。内部重复防御清理仍延期，不修改既定导入合并策略。

第四十七批新增 `startGeminiCacheList(chatId, characterId=null, pageSize=100, previousListTaskId=null)`：每页一个 GEMINI_CACHE_LIST/LIST 任务，pageSize 1..1000，续页必须匹配原页大小/归属/当前私有连接且原 token 未消费。进度保留 listing(caches, hasNextPage, warnings)，token 仅运行时，不自动翻页/重试/恢复续查；stopTask 和现有操作查询共用。账目 model/requestedModel 为 null，通用汇总增 cacheListingCount/reportedCacheListedResources/unknownCacheListingResultCount，不计创建或存储费。详见 [GEMINI_CACHE.md#gemini-cache-listing](GEMINI_CACHE.md#gemini-cache-listing)。

第四十六批新增 `getGeminiCacheResource(referenceTaskId, at=now)`、`geminiCacheResourcesPage(chatId, afterKey="", limit=100, at=now)` 和 `startGeminiCacheCleanup(chatId, referenceTaskId, expectedFrontierTaskIds)`。查询为本地因果状态投影，分页不是稳定快照；清理是明确新 DELETE，必须最新确认/前沿/当前私有连接一致且不在使用，修改/删除不确定先明确 GET。原管理入口也核对最新确认。详见 [GEMINI_CACHE.md#gemini-cache-lifecycle](GEMINI_CACHE.md#gemini-cache-lifecycle)；并非远端枚举或后台自动清理，完整余项保留。

第四十五批：新增 `getGeminiCachePricingRules()`、`replaceGeminiCachePricingRules(rules, expected)`、`installGeminiCachePricingCatalog(rules, expected)`、`estimateGeminiCacheCost(owner, tokenCount, storageSeconds=3600, at=now)`。修改用 PricingVersion CAS、user/catalog 分开替换；估算只读公开输入，不查密钥/发请求。CREATE 开始冻结 pricing，确认创建后存 projectedCost。usageSummary 增 cacheQuotesByCurrency/unknownCacheCreationQuoteCount/unknownCacheStorageQuoteCount，存储预测不入实际 costsByCurrency。详见 [GEMINI_CACHE.md#gemini-cache-pricing](GEMINI_CACHE.md#gemini-cache-pricing)；后文未有价格入口是历史状态，实际结算等全余项仍保留。

第四十四批 usageEventsPage/usageSummary 的 UsageFilter.kind 支持 gemini_cache，provider 为 gemini-cache:{credentialId}，UsageLedgerItem.geminiCache 返回独立操作快照；UsageSummary 增 cacheOperationCount/cacheCreationCount/reportedCacheCreatedTokens/unknownCacheCreatedTokensCount。创建操作数不是成功资源数，不混普通 token；状态/费用/预算与恢复边界见 [GEMINI_CACHE.md#gemini-cache-accounting](GEMINI_CACHE.md#gemini-cache-accounting)，无新价格 API或旧账补建入口。

第四十三批普通生成在选中 Gemini 官方连接且来源 promptCache explicit 时，自动准备记录化缓存子任务/当前进程逐值复用；预览/导入/启动不联网。不新增旧任务续接入口，缓存使用期间管理冲突拒绝；创建失败或缓存失效不自动重建/重发。可查询原操作中的 generationRequestId/generationTaskId/automatic/historyTail，复用生成的完整持久资源索引仍待补。配置/取消/不适用前缀/未知费用和余项见 [GEMINI_CACHE.md#gemini-cache-generation](GEMINI_CACHE.md#gemini-cache-generation)。

第四十二批新增 startGeminiCacheCreate(GeminiCacheCreateRequest)、startGeminiCacheOperation(chatId, referenceTaskId, action, ttlSeconds?, expireTime?)、getGeminiCacheOperation(taskId)、geminiCacheOperationsPage(afterTaskId="", limit=100)、observeGeminiCacheOperation()；stopTask/宿主中断已识别缓存任务。显式调用才创建新操作，正文/密钥不入 payload，unknown 费用不可当零；重启不续发、旧资源变更先新 GET。线程/分页/限制及未完成自动生成/价格/全恢复关系见 [GEMINI_CACHE.md#gemini-cache-operations](GEMINI_CACHE.md#gemini-cache-operations)。

第四十批增加 IO 只读 getModelCatalog(providerId?, query?, offset=0, limit=100)、getModelCapabilities(model, providerId?) 与 previewProvider.fastRequested。目录有界分页/精确能力、价格仅参考；Fast/缓存配置和实际档位计价边界见 [PROVIDERS.md#model-fast-cache](PROVIDERS.md#model-fast-cache)。不自动选模型或安装价目，不重试请求。

第三十九批接桌面 generated/overlay 离线目录合并（39/50 源条目、最终 50）、getProviderCatalog、canonical/legacyId/最长同 host/path 匹配、公开参数模板、同 origin protocolPaths、API-version 和运行时 query_key。标准完整端点不重复拼接，预览返目录 ID/实际鉴权/版本；原值和 query 编码密钥纳入脱敏。未知自定义连接不冒充目录；OAuth/Bedrock 变体仍 Unsupported。Room/format/依赖、来源合并并存/本地保护/中断不重发不变。详见 [目录与路由契约](PROVIDERS.md#provider-catalog-routing)，完整 models/prices/Fast/cache/OAuth/cloud 等及后端全清单继续。

第三十八批 previewProvider 返回 auto 实际协议/baseUrl；hasCredential 指任一选中凭据存在，不代表全部引用齐全。setProviderCredential/removeProviderCredential 也供 header credentialId 使用，预览不返解密值。预设/selectedModel/会话 paramsJson 可配公共头/引用，显式语音预处理同接。格式/预算/覆盖及未支持鉴权见 [PROVIDERS.md#provider-http-options](PROVIDERS.md#provider-http-options)，失败不自动重试。

入口类：`com.simpletavern.core.api.SimpleTavernCore`

第三十七批预设/会话 paramsJson 的已知生成字段统一标准化，四协议的停止词/惩罚/seed/topK 和明确原生思考/摘要/verbosity 见 [PROVIDERS.md#provider-generation-params](PROVIDERS.md#provider-generation-params)。解析和发送前均检查，非法值/明确未适配 extra 不无声忽略，不重试。来源默认/维护模型只取精确 settings 映射或唯一配置；RikkaHub 新导入已知 customBodies/声明能力档位可用，未知 body/header/连接覆盖不冒充全部支持，merge/凭据/任务停止语义不变。

第三十六批 native 检查/恢复核对生成消息与历史用量/冻结费用，显式覆盖不能改坏包外原可核实引用；同源分叉共享原请求事件合法，不重复计费。旧证据不足只 warning，已知运行中生成状态改 interrupted 而保留 opaque 字段。读取预算/错误及边界见 [ACCOUNTING.md#generation-usage-restore](ACCOUNTING.md#generation-usage-restore)，没有新增执行或续接入口。

获取：`SimpleTavernCore.get(context)`（Application 内单例）。旧 `create(context)` 现在也是同一单例的兼容别名，不再创建独立 worker scope。

## 语音合成

第三十二至三十四批预处理/会话 LLM→合成与同来源双选择/实际稿/两账本/native 关联保持，见 [SPEECH.md#speech-preprocessing](SPEECH.md#speech-preprocessing)。第三十五批其他当前全部 LlmClient.stream 调用点也接持久生命周期：聊天/群成员/工具/原生 continuation、总结 chunk、MVU/KG 段/轮各一次调用一行 prepared/running/终态，开始冻结价格，账本在后续业务提交前独立收尾；usageEventsPage 返回 purpose/源/task/candidate/segment/round/turn 与可空 tokenRequestLifecycle。HTTP 前失败不发请求、账目失败不重发；startup/native 只中断 unfinished，旧历史不补造证据。httpAttempted 不证明服务器收到/收费或断电耐久性，完整语义见 [ACCOUNTING.md#token-request-accounting](ACCOUNTING.md#token-request-accounting)，不是整套后端已验收。

第三十一批 `getSpeechDirectory(SpeechDirectoryRequest(presetId?, chatId?, assistantId?, fetchVoices = false, fetchModels = false))` 默认离线。来源/预设/凭据与合成共用选择，不要求 model/voice/text/启用开关，结果冻结 enabled；合成不会因此绕过禁用。支持的明确刷新在短事务后才 HTTP，配置/桌面静态/远端 origin 与逐项 fetch 状态分开，不自动保存、选取、试听、上传克隆、播放、创建任务或记合成费用。MiniMax/GLM/SiliconFlow 音色、OR/SF 模型的当前覆盖与未支持、GLM 待实证/SF 非 TTS 分类/失败预算见 [SPEECH.md#speech-directory](SPEECH.md#speech-directory)。

`previewSpeech(SpeechRequest)` 只读来源设置/预设与加密凭据存在性；`synthesizeSpeech(SpeechRequest)` 是调用者可取消的显式非流式云端合成，可按会话启用预处理，返回 caller-owned SpeechAudio.bytes，不自动播放/保存/挂消息或创建后台任务。支持 MiniMax/GLM/OpenRouter/硅基流动的当前固定 REST 适配；无第一预设/模型/音色或跨来源兜底。配置短事务结束后联网，整个实际串联持 shared data gate 而非 Room 事务；禁用/不支持参数明确失败，密钥只走 setProviderCredential。

第二十九批直接/后台合成都记录独立 _kind=tts 用量事件，SpeechAudio/新 SpeechAsset 附 requestId；不是音频保存或后台任务入口。`getSpeechPricingRules` / `replaceSpeechPricingRules` / `installSpeechPricingCatalog` 使用独立显式 user/catalog 规则和 epoch/revision CAS，支持按次/输入字符/供应商报告字符，模型/来源/作用域/参数/时间条件。请求开始冻结规则，prepared/running/终态同一行，账本短提交后 HTTP，整个事件 shared lease 防原生快照/恢复截断。通用 usageEventsPage/usageSummary 可筛 kind=tts，字符不混入 token、异常成本不计已知费用；未知收费不当零。绑定/缓存操作不新增云端事件，停止/启动/资料恢复不重发。见 [ACCOUNTING.md#speech-accounting](ACCOUNTING.md#speech-accounting)，不是完整供应商账单或所有维度已完成。

第二十七批新增 `startSpeechTask(request, messageCandidateId?)` / `getSpeechTaskProgress` / `observeSpeechTasks`（最新通知）/ `awaitSpeechTask`（返回 SpeechAsset），独立 core-scope worker 与 SPEECH_SYNTHESIS 通知/stopTask/宿主中断已接，启动只清理和终止、不重发。启动时可选消息绑定须 request.text 与当前正文完全相等，文件发布后附件/绑定/成功同库提交，正文变化不挂旧音频。`getSpeechAsset` / `readSpeechAudio` / `getMessageSpeech` 读取 UUID 缓存；`getSpeechCacheStats(assistantId?)` / `clearSpeechCache(assistantId?)` 按来源作用域，null 仅 global。缓存按冻结限额/创建时间有界淘汰，不是永久附件/按文本哈希去重，缺失不自动联网。完整线程/取消/预算/备份与仍待补目录、克隆、预处理、费用和设备证据见 [SPEECH.md#tts](SPEECH.md#tts)，编译不代表整套 TTS 验收。

第二十八批新增 `bindSpeechAsset(SpeechBindRequest)` / `getSpeechMessageBinding(chatId, messageCandidateId)` / `unbindSpeechAsset(chatId, messageCandidateId, expectedAssetId)`。延后绑定在 IO/缓存 mutex/短 Room 事务内读取已有资产，比较 expectedMessageContent 与可选 expectedAssetId，完整旧正文/extras CAS；没有旧 ID 默认只建空引用，完全相同则幂等。原始引用 getter 包括失效引用，解绑只删 _tts 不删共享音频。新 v2 资产冻结 sourceNamespace/spokenText，绑定分别保存实际朗读稿与绑定时正文，目标须同来源；允许显式独立朗读稿但不自动预处理。读/写消息总行至多 256 KiB、task payload 128 KiB；v1 可读，旧无朗读稿证明的缓存不能猜稿延后绑定。普通任务/备份 JSON 现在包含用户朗读文本，不含密钥/HTTP body/音频字节，toString 脱敏不是序列化脱敏。这些本地调用不发 HTTP/创建任务/播放，不改变 desktop/RikkaHub 合并规则。

## 网络搜索

`getWebSearchConfiguration(assistantId?)` / `saveWebSearchConfiguration(configuration, expected, assistantId?)` 提供按 global/来源助手作用域的独立配置与 CAS；凭据仍走加密 `setProviderCredential`。`webSearch(query, assistantId?)` 是调用者可取消的直接独立搜索，不自动新建后台任务。单聊/群聊所有 start 重载新增 `webSearch: WebSearchOptions`（默认关闭），按协议走原生或 Tavily/博查/Brave，失败不回退；生成搜索由原任务宿主承载。原生引用在 GenerationMetadata.search，作用域/预算/暂停/计费和剩余能力见 [ACCOUNTING.md#web-search](ACCOUNTING.md#web-search)。configured 只代表 key 存在，不是账户余额或可用性查询。

搜索账户入口 `getWebSearchStatus(assistantId = null, fetchAccounts = false)` 默认不联网；true 时在短配置/凭据快照事务结束后并行查询 Tavily credits、博查 remaining，逐家返回 state/安全错误，取消仍传播。Brave 返回不支持，不探测账户接口。结果不落账本、不代表发票，缺失值不补零/不猜币种；详见 [账户状态契约](ACCOUNTING.md#web-search)。

## 用量与成本

`getPricingRules` / `replacePricingRules` / `installPricingCatalog` 提供独立 user/catalog 规则和 epoch+revision CAS；`estimateUsageCost` 只估算不写账本。`usageEventsPage` 返回事件/上下文/成本/问题、nextCursor 和 throughSequence；继续翻页须保持 filter/扫描上界，重启/恢复后重新取 cursor。`usageSummary` 分页累计、按币种分开，未知不是零，供应商费用与本地对照不重复相加。记录冻结在对应业务事务，改价不自动重估历史或重发旧请求。字段口径、预算和限制见 [ACCOUNTING.md#usage-accounting](ACCOUNTING.md#usage-accounting)；普通助手工具/搜索/TTS 与完整价格来源及计费维度未完成。

独立搜索计价：`getSearchPricingRules()` / `replaceSearchPricingRules(rules, expected)` / `installSearchPricingCatalog(rules, expected)` 提供按 request/credit 的显式 user/catalog 费率与 CAS。直接/生成搜索都记独立事件，`UsageFilter.kind` 可取 web_search/llm/tts（null=全部），搜索 provider 为 web-search:<provider>；usageSummary 单列 searchEventCount/分提供方 credits，不混入 token。生命周期/未知费用/恢复与预算见 [ACCOUNTING.md#search-accounting](ACCOUNTING.md#search-accounting)，估算不是实际账单。

## 后台宿主与任务观察

- `observeActiveTasks(): Flow<List<TaskSnapshot>>` 是 Room 非终态任务列表，提交后才更新，不要求 UI 订阅来维持任务。
- `observeForegroundHost(): StateFlow<ForegroundHostState>` 提供 `foreground/draining/lastError`。这是当前进程的宿主诊断；任务终态原因则持久保存，平台中断为 `HOST_TIMEOUT/HOST_UNAVAILABLE`，不冒充用户停止。
- `taskNotificationsEnabled(): Boolean` 查询系统通知与后台任务渠道是否允许展示。Android 13+ 的通知运行时授权由未来 UI 处理；禁止通知时仍可能有系统任务管理器中的 FGS，但不能承诺抽屉内停止按钮可见，应用内仍可调用 stopTask。
- 新生成/总结/MVU/KG 任务先等待前台服务提升成功，最长 10 秒。启动限制、提升失败或宿主清理中直接报错，任务不入库/不启动；应由用户稍后新建任务，不重试旧任务。自动后续任务持有短期宿主交接，不能为了自动任务绕过 Android 启动限制。
- 独立通知只停止指定任务。服务空闲 5 秒后退出；注册回滚的 reservation 最迟 30 秒释放。运行/清理时持 CPU 锁，等待审批不持；服务超时/意外销毁先退出平台服务，再由 core scope 取消和保存部分结果。清理未完成时 `draining=true`，不继续接新任务。
- 宿主实现为 dataSync/START_NOT_STICKY。不提供无限后台、厂商保活、锁屏网络或杀进程后恢复保证。独立沙箱 shell/file 与原生备份导出已登记；手工文件复制、来源导入及原生恢复仍未全登记 TaskStore，实际设备尚未验证。

## 线程与取消

- 对外 `suspend` 函数可从主线程调用；内部 IO 使用 `Dispatchers.IO` / Room。
- 生成流：`observeGeneration(chatId)` 为非重放 SharedFlow；慢订阅者丢弃旧通知，不阻塞生成。消息展示以数据库为准，重新订阅应重新分页读取。
- `stopTask(taskId)` 支持单聊/群聊、长期记忆总结、MVU/KG、独立 SANDBOX_SHELL/SANDBOX_FILE 和 BACKUP 导出任务。取消网络、审批/成员延迟、shell 进程组或快照 worker，并等待部分结果保存；已完成回复、正式状态、文件修改与已发布 ZIP 保留。来源导入/原生恢复尚未全接。备份长 Room 快照先发取消信号再等待 STOPPING 写事务，平台中断同样先信号取消，保留第一停止原因。
- 进程重启：`onProcessStart()` 等待一次性恢复，非终态任务及其活动回答标为 `INTERRUPTED`，待审批失效。生成、总结、导入和备份入口先等待恢复；反复调用不会中断新任务。

## 配置与内容

| 方法 | 说明 |
|------|------|
| `listCharacters/getCharacter/saveCharacter` | 角色 |
| `deleteCharacter(id, expectedUpdatedAt, deleteRelatedChats=true)` | 删除角色，返回连带删除的主聊天 ID；其它群聊移除成员 |
| `listPersonas/getPersona/savePersona` | Persona |
| `deletePersona(id, expectedUpdatedAt)` | 删除 Persona 并解除当前会话选择 |
| `setCharacterAvatar/setPersonaAvatar` | 复制用户 content URI 并绑定托管图片 |
| `clearCharacterAvatar/clearPersonaAvatar` | 取消绑定，保留共享图片 |
| `getAvatarAttachment/getAvatarFile` | 相对引用的托管元数据／应用内部图片文件 |
| `previewCharacterCardImport/commitCharacterCardImport/importCharacterCard` | ST JSON/PNG 单卡预览草稿、明确保存或一步导入；新增独立本地记录 |
| `characterOpeningMessages(characterId)` | 主开场白与候选；新建聊天按原 openingMessageIndex 选择，将启用、非空的普通开场白保存为同一节点的候选，之后沿 selectCandidate 切换 |
| `listWorldBooks/saveWorldBook` | 世界书 |
| `getSettingsJson/saveSettingsJson` | 全局设置 JSON |
| `getAssistant/saveAssistant` | 助手配置 |
| `listPresets/getPreset/savePreset` | 模型预设 |
| `listAssistantConfigs/getAssistantConfig/saveAssistantConfig` | 独立来源配置与助手配置；不改全局选择 |
| `previewProvider(chatId, characterId?)` | 解析当前会话/群成员的连接，返回预设、协议、地址、模型、credentialId 和是否已有凭据；不返回密钥 |
| `setProviderCredential/hasProviderCredential/removeProviderCredential` | credentialId 对应 Keystore-backed 加密存储；不进入普通 JSON/快照；原生备份仅显式 includeCredentials+独立密码时另行加密包含 |
| `listChats/getChat/saveChat/deleteChat/createChat` | 会话 |

## 消息与生成

createChat 末尾可选 `userPersonaId: String?=null`、`useDefaultPersona: Boolean=true`：非纯 AI 时显式 Native ID 优先，未提供时按角色自己的来源继承 selectedPersonaId；false + null 明确不用默认 Persona。默认映射/记录缺失不选择其它人，非纯 AI 的显式缺失 ID 返回 NotFound；纯 AI 不绑定 Persona。选择与开场白/聊天一起提交，不修改全局设置。Native、Desktop namespace 与 Rikka chatDefaults 的具体隔离及旧配置边界见 [CHAT.md#message-editing](CHAT.md#message-editing)。

创建的末尾还可传 `firstMessageCharacterId` 和 `useOpeningMessage`：单聊默认主角色开场，群聊默认无开场，指定成员即可启用其开场白；openingMessageIndex 对该成员生效。群聊成员保持传入顺序去重，只在主角色缺失时补到开头；开场消息绑定实际成员，且不复制单人来源预置消息。详细组合和单聊来源消息边界见 [CHAT.md#message-editing](CHAT.md#message-editing)。

可选的 `pureAiMode: Boolean?=null` 在创建事务内保存会话覆盖，null 继承原来源模式；普通开场白保存前沿原方法替换 `{{char}}`/`{{user}}`，纯 AI 用“用户”，候选排除 chip=false。原角色、旧聊天与来源预置消息不回填，不新增模板层；详见 [CHAT.md#message-editing](CHAT.md#message-editing)。

创建群聊还可传 `groupMvuPreset="off"` / `groupMvuPresetCharacterId=null`：默认关闭 MVU、不复制主角色状态，但按成员合并正文规则为会话快照；inherit_member/fork_session 明确复制所选成员模式、指令与非空初始表。模板 ID 进入原 overrides JSON，已接桌面导入、原生角色关系和删除解绑，状态重建使用选定模板；已有聊天不回填。具体预设及 ID/规则顺序见 [CHAT.md#message-editing](CHAT.md#message-editing)。

| 方法 | 说明 |
|------|------|
| `pageMessages(chatId, limit, offset)` | 消息节点分页（含候选与选中） |
| `getMessageNode(nodeId)` | 按节点 ID 读取完整节点及候选；重新生成完成后用它刷新目标 |
| `getMessageNodeForCandidate(candidateId)` | 将检索命中的候选 ID 定位为完整消息节点；读取不改变选中项 |
| `appendMessage(chatId, candidate, parentNodeId=null)` | 手工追加消息，不发起模型/工具任务；新附件按托管 ID 解析 |
| `selectCandidate/addCandidate/editSelected` | 候选版本 |
| `editSelected(nodeId, edited: MessageCandidate)` | 编辑选中候选的正文、思考、媒体与发言人快照；固定来源/请求/费用字段不改 |
| `deleteMessageNode(nodeId)` | 删除节点及其候选；不截断后续历史 |
| `deleteCandidate(nodeId, candidateId)` | 删单候选并返回更新节点；最后一项删除返回 null |
| `truncateMessagesAfter(nodeId)` | 保留指定节点，删除后续节点；返回删除节点数 |
| `editSelectedAndTruncate(nodeId, content)` / `MessageCandidate` 重载 | 原子编辑选中候选并截断后续历史，返回编辑节点 |
| `searchMessages(chatId, query)` / `(chatId, query, limit, offset=0)` | 正文包含检索；旧入口首批 50 条，分页入口按消息顺序/候选顺序返回，query 中的 %/_/反斜线按字面匹配，空白查询无结果 |
| `forkChat/forkLineage` | 原子硬分叉；重映射节点父引用和可解析生成元数据，清空记忆/上下文覆盖锚点，不复制 memory/MVU/KG 或任务 |
| `branchChat(sourceChatId, newTitle=null)` | 完整历史/候选与正式 Memory/MVU/KG 快照；保持类型，源会话保留 |
| `promoteChatToGroup(sourceChatId, memberIds, ...)` | 普通单聊完整复制为新群聊，源单聊不改，不加开场白；契约见 [CHAT.md#chat-copy](CHAT.md#chat-copy) |
| `startGeneration(...)/stopTask/observeGeneration` | 生成与停止 |
| `startRegeneration(chatId, nodeId, webSearch=默认)` / 显式 `ProviderConfig` 重载 | 对已有 assistant 节点重新生成；成功追加并选中新候选，细节见 [CHAT.md#message-regeneration](CHAT.md#message-regeneration) |
| `startGroupGeneration(chatId, userText, options)` | 后端串行调度群聊；options 为 GroupGenerationOptions(rounds=1, memberIds=[], respectProbability=true)；保留显式 ProviderConfig 重载 |
| `getGroupGenerationProgress(taskId)` | 读取持久计划、各轮成员、当前成员、跳过/成功/停止等状态及本轮生成消息 ID |

错误：`StError.Conflict` 表示同会话已有生成任务。

2026-10-10 重新生成：`startRegeneration` 的详细并发、失败与持久化语义见 [CHAT.md#message-regeneration](CHAT.md#message-regeneration)。普通 `startGeneration(..., userText=null)` 仍按原行为追加 assistant 节点；`addCandidate` 只写候选，不发起模型请求。

2026-10-10 完整字段编辑与手工追加：`MessageCandidate` 重载只更新明确的可编辑字段；调用方应读取当前选中项再 copy，空列表/null 明确清除可编辑值。附件沿托管 ID 解析，原来源缺文件不阻断未换附件的编辑。正文 String 重载仍保留其余字段；完整语义见 [CHAT.md#message-editing](CHAT.md#message-editing)。

2026-10-10 用户输入身份：生成登记前固定当前 Persona 的 ID/名称/头像引用，worker 按原时机保存；群聊不重复追加、重新生成不补新输入。手工 user 消息未提供 sender 字段时填同一身份，无 Persona/纯 AI 用稳定“用户”名称，显式快照保持。历史、{{user}} 渲染和记忆来源标签使用已保存值；不回填旧消息或冻结全部后续配置，详见 [CHAT.md#message-editing](CHAT.md#message-editing)。

推荐调用 `startGeneration(chatId, userText, interjectCharacterId?)`，自动解析会话预设和加密凭据。原显式 `ProviderConfig` 重载保留。先调用 `previewProvider` 取得 credentialId，再配置凭据；RikkaHub 同供应商的模型共享 sourceProviderId 对应密钥，desktop 预设各自独立。原生会话无预设时读取全局 llm，默认密钥 ID 为 `default`；导入会话缺预设或引用无效时明确报错，不隐式借用其他来源连接。

群聊未指定 interjectCharacterId 时，startGeneration 默认调度一整轮，而不是把承载角色当唯一发言者；插话只执行该成员一次，不受概率筛选影响。startGroupGeneration 可指定 1..20 轮、成员子集及顺序，最多 200 个计划位置，空 memberIds 沿用会话顺序。群聊须有至少两个不同且存在的成员；概率为 0..1，groupDelayMs 为 0..60000。每轮独立概率筛选，沿用 desktop“全部未选中时随机保留一人”行为，所以全部概率 0 也不是关闭群聊。计划在启动时固定并持久保存，失败/重启不重新抽签或重放。

一个调度对应一个 GROUP_GENERATION task，与普通生成共用同会话排他约束。用户输入只落盘一次；每个成员开始前重新读取会话、角色、模型配置与选中历史。各供应商请求账目独立收尾，再原子提交该成员最终回答（附 usage/费用快照）和 SUCCEEDED 进度，之后开始下一个成员；出错即终止剩余计划，已成功结果不回滚。停止或重启保留成功/跳过位置，把未执行/活动位置标 STOPPED/INTERRUPTED，不自动续跑。没有暂停后续接原任务的 API；可显式创建新的剩余成员计划。

群聊原 user 历史在请求内标记 `[已保存姓名]: `；纯 AI 转出的 system 片段沿同一归属，附件补助不重复标记。正文存储/世界书匹配保持原值，标签先进入模板和最终预算估计；单聊/助手和原生 system/tool 不套标签，旧无姓名仅沿原用户名回退，不回填历史。详细范围见 [CHAT.md#message-editing](CHAT.md#message-editing)。

普通重载按成员 preset/model/temperature/topP 与 reasoningEffort 解析连接；显式 ProviderConfig 重载使用调用方提供的共同连接（不改用成员 preset），仍应用成员 model/temperature/topP/reasoningEffort。两条路径均使用原 Fast 转换，显式连接不继承预设的其它字段，模式字段/成员 fastMode 的范围见 [PROVIDERS.md#model-fast-cache](PROVIDERS.md#model-fast-cache)。成员性格/场景开关进入上下文，提示词标明当前角色与成员名单；其他成员的回复带归属转换为历史输入，完整工具往返转换为惰性历史文字，不冒充当前成员的 assistant/tool 调用。纯 AI 模式保留 system 输入语义。groupSystemInjectDepth/AlwaysAtBottom 已进入整段 system 的实际插入位置，工具往返边界使用安全位置。不能以此声称全部群聊设置或显式连接的所有覆盖行为已对齐/运行验收。

整轮流仍只有一个最终 Done；成员间不发 Done 以免前端提前结束。Delta/Usage 为当前请求临时通知，展示与角色绑定以数据库候选及 getGroupGenerationProgress 为准。整轮成功后在最后一条回复边界触发一次自动总结；失败/停止不把整轮标为成功。缺提供商/凭据等错误在任务中可查，不在 UI 中运行循环或自动换来源重试。

forkChat 先等待启动恢复；有活动生成的源会话拒绝分叉。锚点可为节点或其选中候选，来源关系统一记录原选中候选 ID 和 1-based 节点序号。源快照、新会话与全部候选同事务保存，任何失败整段回滚。节点/候选使用新 ID，复制范围内的 parentNodeId 重映射，范围外/无效父引用清空；可解析 generationMetadata 的 chatId/messageId 改为新会话/候选，requestId 仅保留历史请求来源，不创建关联任务。附件复用既有托管文件引用，工具调用仅为历史，不重放。保留 preset/Persona/角色等内容引用与 sourceNamespace 配置范围，sourceId 清空，不冒充原导入实体；清空正式记忆、MVU/KG、overrides.longTermMemory、上下文起点和自动总结覆盖，询问倍数重置。未知原始 JSON 仍为来源记录，不猜测改写其字段。

目前支持明确选择的四种协议；`auto`、OAuth、Azure/Vertex/Bedrock 模板及非默认鉴权尚需独立适配。仅转发已支持的生成参数，不执行导入设置中的自定义 headers/tools。允许无鉴权的本地服务需配置 `allowUnauthenticated=true`；Android 仍遵守应用网络安全策略。普通配置接口不要写入密钥；已有历史明文配置不会被这轮自动清理，备份安全完整处理仍在后续范围。

Chat Completions 的输出限制默认发 `max_tokens`，适用于常规兼容服务；只接受新版字段的模型/提供商可在预设配置里设 `maxTokenField="max_completion_tokens"`。不会按模型名称猜测协议或参数格式。会话参数中的 null 表示继承，camelCase/snake_case 参数先归一化再按会话覆盖默认值。

四种协议已接通客户端 function tools，需调用方显式提供规范化的 Chat Completions 形状 toolsJson，默认不启用工具。HTTP 边界分别转换 Responses 扁平函数/input items、Anthropic input_schema/tool_use/tool_result、Gemini parametersJsonSchema/functionCall/functionResponse；同一工具往返依旧共用现有审批、取消与执行服务。最多 8 轮模型请求；同一任务重复 call ID 被拒绝，只有完整成功流中的完整参数才进入审批和执行。现有 sandbox shell 的强隔离尚未实现，不应用于不可信命令。

`GenerationMetadata.providerTurn` 和 `ContextMessage.providerTurn` 保存已完成原生 assistant 输出及协议/模型；StreamEvent.Done 可携带该结构，后端生成会在候选元数据保存，下一轮无需客户端组装。Responses 使用 store=false 并保留 reasoning/output items，Anthropic 按索引拼合 thinking/signature/tool input，Gemini 按原 parts 顺序保留 thoughtSignature，不计算或验证内容哈希。opaque provider 状态不是凭据，不交给工具执行；只在同协议/同请求模型且正文/规范化调用仍一致时回传，不把另一模型的签名拼到当前请求。

已完成工具组的声明和所有结果必须成组匹配，缺项、孤立/重复结果在请求前失败。跨协议/模型切换，或旧正文被用户编辑/正文投影修改时，有原生状态的旧工具组转为明确标注的惰性历史资料；Gemini 的无原生状态旧工具组同样处理，避免伪造必需签名。结果文本仍保留，不重新执行旧工具。没有原生状态的其他协议历史按规范化函数记录转换。群聊其他成员仍使用惰性归属历史。native JSON 至多 4194304 字符、参数至多 1048576 字符、单轮至多 64 个调用；SSE 单事件/行原上限不变，全请求 data 行合计至多 16777216 字符，超限保留已保存片段并失败，不截断后假装成功。上下文估算计入原生状态体积。

本批仅客户端函数工具；提供商内建 server tools、Gemini 可选 partialArgs 流、未适配的内容块或参数仍明确 Unsupported/报错，不宣称官方 API 的所有新能力均已支持。四协议接线和构建通过，尚无真实上游调用证据。

## 记忆 / MVU / 图谱

| 方法 | 说明 |
|------|------|
| `getMemory/editMemory/summarizeMemory` | 长期记忆；编辑带版本校验 |
| `startMemorySummary(chatId, rebuild=false)` | 立即返回排队的总结 job；后台解析本会话的提供商。保留显式 ProviderConfig 重载 |
| `getMemorySummaryJob/listMemorySummaryJobs` | 读取状态、实际源消息、部分/完整结果、错误与已提交的覆盖边界 |
| `getMemorySummaryProgress(jobId)` | 固定源终点、总/已完成段数、当前段、已处理消息数、最后完整草稿与正式应用版本 |
| `pendingMemorySummary(chatId)` | 非静默自动总结的待确认任务，不属于 sandbox 工具审批队列 |
| `confirmMemorySummary(jobId)/dismissMemorySummary(jobId)` | 确认后才排队请求；拒绝终止该待确认任务并增加下次询问倍数 |
| `buildContext` | 返回注入诊断（含 memory/mvu/kg 是否进入） |
| `getMvu/saveMvu` | MVU 状态乐观锁 |
| `getKg/saveKg/upsertKgEntity/upsertKgRelation` | 知识图谱 |
| `previewStateMaintenanceProvider(chatId)` | 无密钥维护连接预览；来源作用域的 mvuModel 优先于普通会话模型 |
| `startMvuUpdate(chatId, includeKg=true)` | 后台 directive/regex MVU 维护；includeKg 仍受 knowledgeGraphEnabled 开关限制。保留显式 ProviderConfig 重载 |
| `startKgUpdate(chatId)` | 独立 KG 工具维护；KG 开关关闭则任务明确失败。保留显式 ProviderConfig 重载 |
| `startStateHistoryRebuild(chatId, maintainMvu=true, includeKg=true)` | 显式从角色初始表/空图谱分段重建全部有效选中历史，全部成功后替换所选正式状态；也有显式 ProviderConfig 重载 |
| `getStateMaintenance(taskId)/listStateMaintenance(chatId, limit=100)` | 返回任务状态、源消息、状态/规则草稿、部分说明、工具记录、预期/已应用版本；不返回密钥 |
| `listMvuLogs(chatId, limit=100)` | 读取持久的排队/成功/失败/停止/中断日志；limit 为 1..500 |

同一会话最多一个未结束的总结任务，手动与自动共用此约束；与聊天生成可并行。`summarizeMemory` 保留为等待后台任务结束的兼容接口，取消等待者不会取消 worker；需要停止时调用 `stopTask(job.id)`。

成功聊天回复落盘后触发一次自动总结检查，范围止于这次回复，不把后来追加的消息当成已总结。`autoMemorySummaryEveryN<=0/null` 关闭；按未覆盖、有正文的选中 user/assistant/system 消息统计，跳过工具、独立思考及未成功生成的回答。`autoMemorySummarySilent=false` 时先存 AWAITING_APPROVAL，前端通过 pending/confirm/dismiss API 完成确认；无前端确认就不发送总结请求。拒绝后的 `autoMemorySummaryNextAskTier` 递增，下次阈值为 N*tier；静默模式忽略倍数，总结成功后重置 1。普通停止不增加询问倍数。

增量总结从现有记忆锚点（没有时用会话总结锚点）之后读取，保留旧记忆。单次取最早连续的至多 500 条符合条件的消息，提示词上限 100000 字符，不截断一条消息；覆盖只推进到实际读过的末条。其后未读消息留给后续总结，不连环发送自动请求。阈值计数和源读取改为每页 200 个选中节点，到达阈值/输入前缀便停止，不为一次增量请求加载全历史。锚点被删/选中项改变、旧记忆或第一条消息超预算时明确失败，不猜测重置。

需要重建旧历史时显式使用 `startMemorySummary(chatId, rebuild=true)`（也保留显式 ProviderConfig 重载）。准备阶段在事务内分页固定当前完整正文与终点，不带旧记忆作为模型输入。每段至多 500 条完整源消息，含发送者前缀的源正文合计至多 50000 字符；每次请求合并前一段完整草稿，重建草稿上限 40000 字符，请求总输入仍不超过 100000。不是截断正文，也不运行模型工具。逐段累积是有损的模型摘要，不保证细节逐字保留；实际内容质量需上游验证。

一次重建最多扫描 20000 个选中节点、完整源快照 JSON 累计 20000000 字符、128 段；任何上限、单条超 50000、重复消息顺序或空有效历史都明确失败，不提交部分重建。全部段完成前正式记忆保持不变，ContextBuilder 继续使用旧记忆。全部完整成功后，同一事务分页重读并比较固定源/选择/顺序、记忆完整值与版本/锚点，再一次提交最终记忆、覆盖、job/task 和 appliedMemoryVersion。第三十五批各段供应商账目已在调用结束独立保存，不与应用事务一并回滚；每段 requestId 独立、失败/停止记录可得 usage，不合并伪请求。固定终点之后追加消息允许保留。

`MemorySummaryProgress` 存在 task.payloadJson，不改 Room v2。totalChunks/completedChunks、activeChunk（从 1 开始）、sourceMessageCount/processedMessageCount、sourceThroughMessageId 表示计划与处理进度；draftCoveredThroughMessageId/draftText 是最后一段完整草稿，只有 appliedMemoryVersion 非空才表示正式应用。当前段的部分流文字仍从 job.resultText 读取，换段时可为空；此前完整草稿不会被该空值覆盖。每 500ms 与每段边界保存，checkpointRevision 拒绝旧定时快照覆盖新段或准备快照。停止/失败/冲突保留可查进度和草稿但不推进正式覆盖，重启标中断、不自动续接/重发；重新启动显式重建会从头开始，不复用旧任务草稿。旧任务有 options、无 progress 时返回零计数兼容值，缺 options 的历史任务明确 NotFound。

结果每 500ms 记入 summary job 的 resultText。只有全部请求完整结束、有正文、源消息及候选选择未变化、记忆完整值与版本仍一致时，才将正式记忆/锚点、`lastAutoMemorySummaryAfterMessageId`、job/task/进度同事务提交；第三十五批各次 usage 在调用结束独立保存，拒绝应用不抹供方账目。覆盖终点之后新增消息不阻止提交；原文编辑、候选切换、删除/插入覆盖区消息、手动改记忆会拒绝应用。只比较实际字段/版本，不哈希。失败/停止保留 resultText/完整段草稿供查看，不推进覆盖/回放工具/自动重试。

手动编辑记忆保持现有覆盖锚点，版本仍递增；若修改的是已经覆盖的旧历史，当前记忆不会自动追溯重建，需显式 rebuild。重启将未完成的总结 job/task 同步标为 INTERRUPTED，保留已保存部分，不重发网络请求；突然杀进程可能损失最近 500ms 片段。当前是本会话模型的文本总结，不是桌面完整助手 Agent，也不读取图片或执行工具。

### MVU/KG 维护任务

普通回复或群聊整轮成功提交后，独立后台先扫描正文规则，再检查 MVU 启用状态：单聊沿用绑定角色的 mvuEnabled；群聊优先 groupMvuEnabled，未设置时沿用承载角色。关闭时仍可保存正文投影，但不启动维护。模式/指令按 overrides.mvuMode/mvuDirective → 角色配置解析，支持 directive/regex；regex 自动运行必须有有效提取，不能以整段对话代替提取队列。手动 startMvuUpdate 可在关闭自动运行时显式请求，两种模式都可用；手动 KG-only 不要求 MVU 自动启用，不读写 MVU。

同会话 MVU 与 KG 共用一个串行 writer，手动启动与活动维护冲突；自动信号在已有任务后最多留一个待处理任务，后来的成功回复合并为这个待处理任务的最新终点，不修改已运行任务的源。取消页面等待不取消宿主 worker。停止排队任务不发请求，停止运行任务取消网络/后续本地工具；启动恢复将所有未结束维护标 INTERRUPTED，不重放请求、草稿或工具。历史任务若没有维护 payload，getStateMaintenance 明确 NotFound，仍可通过 getTask/recentTasks 查询其终态。

普通指令任务读取固定成功终点之前的最新 50 个选中节点，使用其中有正文且完整的 user/assistant/system 消息；不把工具、思考、失败/中止回答作为新事实。普通 regex 模式每个任务消费一条最早的有效待处理消息，其提取值是新事实，终点前最新 50 个节点仅用于解释上下文。提示词上限 100000 字符，超出明确失败而非截断。自动成功时设置 lastStateMaintenanceAfterMessageId；这是最近维护终点，不代表窗口外全部历史已逐条消费。regex 队列自己的 consumed 状态才是消费依据，不能用该锚点跳过重新扫描产生的旧消息提取。普通手动更新不推进自动信号标记，硬分叉清空它；显式重建另见下节。

维护连接继承会话预设和相同来源的加密凭据，不使用群成员的连接。来源 settings.mvuModel 优先，其后同源 llm.defaultModel/modelCandidates，再沿用会话连接的模型；导入会话不借用另一来源或全局 mvuModel。预览入口可确认实际模型。显式 ProviderConfig 原样作为连接，由调用方负责模型选择。四种协议均使用同一限定维护工具循环，HTTP 原生编码由 LlmClient 完成，不改成无工具的整份 JSON 替换。

限定工具集：mvu_get_session_state、mvu_define_table、mvu_set_cell、mvu_get_chat_context，以及可启用的 kg_upsert_entity、kg_delete_entity、kg_upsert_relation、kg_get_context、kg_query。另提供固定日志快照 read_mvu_logs；普通 MVU 更新任务还可用 chat_content_regex_manage、character_content_regex_manage 操作会话/载体角色规则草稿，历史重建不声明这两个写工具。没有 shell/文件/网络/任意配置入口，不进入沙箱工具审批队列。参数类型、字段、ID、实体类型和置信度由代码检查；新增实体 ID 由后端产生。两个 KG upsert 可选 source_message_id，仅允许当前固定上下文中的候选 ID；省略时以当前段/窗口终点为来源，不保证精确首次原文定位。已有首次溯源不覆盖。表定义采用增量合并，不清空已有单元格；实体按 name+type 合并属性，删除采用墓碑并清除相邻关系。图谱关系允许字面量客体，但主体必须为活动实体，已删除实体不能作为客体。工具参数错误返回结构化结果；模型可修正，无效操作不改变任何草稿。

最多 8 次模型请求、128 个工具调用；累计请求上下文上限 200000 字符，说明文本上限 60000，MVU/KG/规则草稿合计上限 100000，任务诊断 JSON 上限 250000。达到限额任务失败，不把中间草稿写入正式状态或角色/会话。工具只在本轮流确认完整后执行。说明每 500ms 保存，工具完成后即时保存；checkpointRevision 防止旧定时快照覆盖较新的工具记录。部分文字/工具草稿可查询，但 ContextBuilder 从不注入这些草稿。

只有 Agent 完整结束、消息内容/顺序/选择、维护指令/开关及相关 MVU/KG 的完整值与版本未变时，才在一个 Room 事务保存两个正式状态、自动终点、task/log 和 usage。终点之后追加消息不阻止提交；编辑源或正式状态使草稿拒绝应用，无哈希。每个有变化的正式状态只递增一次版本，没有变化则不创建/改写对应状态（角色初始表可作为首次 MVU 草稿并在成功时初始化）。提交失败整体回滚，诊断中的 appliedVersion 清空；错误/停止/断流/重启都不推进信号、不写正式状态、不自动重试。

KG 手工 upsert 的读取、合并与版本检查现在也在同一事务；同 name+type 更新保留既有实体 ID、合并属性，不悬空旧关系；按 ID 重命名若与另一实体冲突则拒绝。关系保留首次建立溯源，支持字面量客体并校验 0..1 置信度。角色扮演上下文仅在 MVU runtime 启用时注入正式 MVU/KG，KG 还受自身开关控制；不注入悬空主体/已删除实体关系。群聊逐成员同步和专用前台宿主仍未完成，四协议维护尚待真实上游验收。

### 显式 MVU/KG 历史重建

`startStateHistoryRebuild` 是替换式重建，不在旧正式状态上增量叠加，也不会随聊天自动启动。maintainMvu/includeKg 至少选一项；MVU 从角色 initialStateTablesJson 或空表开始，KG 从空图谱开始，未选状态不读写。请求 KG 而 knowledgeGraphEnabled=false 会明确失败，不静默降级；可用 maintainMvu=false/includeKg=true 单独重建 KG。使用相同维护连接、任务 kind、writer、进度查询和 stopTask，无新表或依赖。重新重建可能产生新的实体 ID，手工补充而历史未包含的状态不会自动保留，调用方应明确展示替换语义。

准备阶段在 Room 事务内每页 200 个节点读取固定历史，最多 20000 个选中节点、20000000 字符完整源快照；只处理有正文的完整 user/assistant/system。严格检查顺序，按至多 50 条/25000 字符正文投影 JSON 分段，最多 256 段，单条超限不拆分或截断。模型和上下文工具仅获得本段正文、角色与发送者归属及时间，不传推理、工具往返、附件、usage 或生成元数据；最终比较仍使用完整原始候选字段。每段从累计状态草稿继续、重新开始工具对话；初始提示词上限 150000 字符，其余普通草稿/上下文/诊断预算不变。每段至多 8 次请求/128 个调用，整次至多 1024 次请求/4096 个调用；达到预算明确失败，不交付半份状态。分段模型维护仍可能遗漏或误解事实，未做真实上游验收。

regex 重建按固定有效规则在任务内遍历全部符合条件的历史正文，不受近期 scanDepth 的窗口限制；跳过系统消息和 orderIndex=0 的 assistant 开场。原文、影子记录和规则不改写，不提前消费队列；源正文仅作为提取事实的解释上下文。每段提取 JSON 至多 50000 字符，任一规则执行错误就失败；无提取段不请求模型，整次没有提取则明确失败并保留旧状态。成功提交时才消费终点内已存在、有效且由本次覆盖的 pending 项，不创建完整历史的影子队列。原有 consumed/stale/empty 项不重新排队；以后规则变更或显式扫描仍可能产生新待处理项。

`StateMaintenanceProgress.historyRebuild` 标识此流程。totalSegments/completedSegments、activeSegment（1-based）、historySourceMessageCount/processedHistoryMessageCount、historyThroughMessageId 是计划/进度；draftCoveredThroughMessageId 仅指最后完整段终点。draftMvu/draftKg 可含当前未完成段工具变更，只有 appliedMvuVersion/appliedKgVersion 才是正式写入。sourceMessageIds/partialText/toolRecords 只描述当前或最后一段；换段重置，完整段 ID/说明/工具记录保存 history_segment_complete 日志（draftOnly=true），用 listMvuLogs 读取。historyRequestCount/historyToolCallCount 是发起/收到计数，historyExtractionCount 包含当前段提取，不等同正式应用。说明每 500ms、工具/段边界即时保存；第三十五批各轮 usage 在调用结束独立保存，不与段日志或最终状态提交一并回滚，各请求独立 ID。

全部段成功后，在一个 Room 事务分页比较全部固定源/选择/顺序、载体/来源、维护配置、固定有效规则、所选正式状态完整值与版本及信号标记，再提交状态、有效 pending 消费、任务/进度/日志和当前 usage。MVU 重建成功更新 lastStateMaintenanceAfterMessageId 到固定终点，KG-only 不改 MVU 信号。后面追加消息可保留，期间无关会话编辑也不覆盖。任何失败/停止/断流/冲突都不替换旧正式状态或消费队列；已完成段的诊断和 usage 可保留。重启仅标中断、不自动续发；新显式任务从头重建，不使用旧草稿续接。

### Agent 规则工具与日志读取

`chat_content_regex_manage` / `character_content_regex_manage` 接受 `operation=list|upsert|delete`。list 不接受额外参数；upsert 接受 rule 对象，已有规则仅按给定字段更新（含 id），新规则省略/留空 id，由后端生成；指定不存在的 ID 明确 NotFound。delete 接受 rule_id，不存在也明确失败。字段类型与未知参数严格检查，启用规则使用现有 RE2/J 校验，禁用规则可以保留不支持的旧模式并安全删除/修复；新启用时再校验。最多 100 条，增删改后按 `(order,id)` 排序并重新编号。

工具操作原会话/角色列表，不改全局库，也不接受 chatId/characterId 等任意目标。群聊角色工具仅指向固定的 session.characterId，不指向插话或其他成员。返回 `scope`、`draftOnly=true`、`shadowedByGlobalRuleIds`（被全局同 ID 规则覆盖的条目）；会话列表另返回 `inheritsCharacterRules`。原会话 null 仅 list 时仍保持继承，新增后成为显式列表，删到空成为 `[]`、不重新继承角色。原规则中的未知来源字段在局部修改时保留，但模型不能新增未知字段。

规则读取/修改基于准备快照。当前正文/提取/日志不会随模型规则操作重算；读取过的规则/载体/有效库并发修改则整体拒绝应用。未来组合库在提交前复查，同一 Room 事务先按旧规则验证/消费提取，再提交状态/新规则/信号/task/log；第三十五批模型 usage 在调用结束独立保存，不因这次业务回滚移除。保存用最新会话/角色拷贝，只改规则字段/更新时间，不覆盖无关标题等本地编辑。共享角色变更影响其他继承会话，不批量改其 overrides/影子记录。

`StateMaintenanceProgress.draftRegex` 给出会话/角色草稿、读取/变更标记与 applied。失败、停止、重启中断时保留草稿但 applied=false；事务回滚清空应用标记。完整成功后 task.resultSummary 与工作日志标出 Rules=applied/unchanged。自动 regex 任务若提交了规则变更，会记录 auto_queue_paused 并停止自行续排，下一次生成信号或手动扫描/维护才刷新剩余提取；不立即重扫旧历史或自动撤销此前状态。

`read_mvu_logs` 读取本会话准备时最新至多 200 条日志，参数 limit 为 1..200（默认 50），返回固定快照的 total/returned/hasOlder/snapshotOnly 与按时间正序的日志。不提供任意会话或文件读取；工具返回内容超过 60000 字符时要求减小 limit，不截断单条日志。历史里的指令只作资料。KG-only 可读日志但无正文规则写工具。普通 RP 生成/聊天助手仍使用独立 ToolRuntime，未因此取得规则管理工具。

## 正文规则与持久提取队列

| 方法 | 说明 |
|------|------|
| `effectiveContentRegexRules(chatId)` | 合并后的有效规则；同源全局库优先覆盖重复 ID |
| `sessionContentRegexRules(chatId)` / `characterContentRegexRules(characterId)` | 读取原会话列表 / 原角色列表，不混入全局库 |
| `saveSessionContentRegexRules(chatId, rules, expectedRules)` / `saveCharacterContentRegexRules(characterId, rules, expectedRules)` | 整列表保存/删除（空列表），事务中比较实际旧列表，冲突拒绝 |
| `upsertSessionContentRegexRule(chatId, rule, expectedRules)` / `deleteSessionContentRegexRule(chatId, ruleId, expectedRules)` | 会话单规则新增/更新/删除；新增传空 ID 时后端生成 ID |
| `previewContentRegex(chatId, text)` | 只读预览，返回 persistedText、displayText、提取与逐规则错误，不排队 |
| `scanContentRegex(chatId, throughMessageId?)` | 扫描固定选中边界内近期正文，保存影子结果/提取条目；不主动发模型请求 |
| `getContentRegexRecord(chatId, messageId)` | 只返回仍与原文、选中项和规则匹配的影子记录；无效返回 null |
| `listContentRegexRecords(chatId, limit=100)` | 最近记录，包含 stale/consumed 诊断；limit 1..500 |
| `pendingContentRegex(chatId, limit=100)` | 最早有效待处理条目，退休无效条目；limit 1..500 |

使用 `ContentRegexRule`，最多 100 条有效规则，按 `(order,id)` 排序。先取同来源 settings.contentRegexRuleLibrary，再会话列表；会话列表为 null 时继承载体角色列表，显式 `[]` 不继承角色。导入会话不会借另一来源/全局库。`contentRegexEnabledByRuleId` 覆盖开关，`contentRegexScanDepthDefault=50` 与逐规则 scanDepthOverride 支持 1..500；扫描节点窗口上限 500。只扫描完整 user/assistant 正文，排除 orderIndex=0 的 assistant 开场白。

remove/replace 分别独立作用于上下文正文与展示正文；extract 仅提取、不改正文；extract_and_replace 从上下文正文提取，仅替换展示正文。支持 first/global、whole_match/capture_group（默认组 1，未参与或缺失组不产生提取值）、`$N`/`$$` 和数字 `\\1`/`\\g<1>` 替换。匹配默认 MULTILINE+DOTALL；支持 `/pattern/imsu`，u 保留 Unicode 引擎默认语义，不接受重复/其他旗标。

执行引擎为 [RE2/J 1.8](https://github.com/google/re2j)，不是 Python/JS 全语法兼容层：模式反向引用、前后查找、Python 专用命名语法/命名替换等明确拒绝，不退回不受控的回溯引擎。模式最多 4000 字符、编译程序至多 10000 指令、输入至多 50000 字符、每条规则至多 128 次匹配，投影至多 100000 字符；全部提取至多 256 条/60000 字符。匹配有协作取消/约 150ms 字符访问预算，不宣称硬实时超时或进程沙箱。失败规则整条无效，不留下部分替换/提取；之前成功规则保留，错误随记录返回。整条输入超预算不应用任何规则，原文保留。

`MessageCandidate.content` 始终是原文；影子输出在新 Room v2 的 content_regex_records 保存，v1→v2 仅新增表/索引，无删库回退。ContextBuilder 使用有效 persistedText；未扫描的新用户输入或编辑消息在读上下文时只读计算，不排队。UI 需要调用记录/预览接口取 displayText；pageMessages/search/export 仍返回原文。世界书触发、总结、维护解释上下文目前也使用原文，不宣称所有正文消费者已统一。

聊天成功后扫描，再按 MVU runtime+regex 模式触发维护；未提取到内容不发维护请求。手动 startMvuUpdate 先扫描，再处理一条最早提取；空队列会创建可查询的失败任务，不伪装成功。自动任务每次只处理一条源消息，成功后继续固定信号终点内下一条；新信号合并为最多一个排队任务，不改运行快照。失败/停止不自动续排；后续新自动信号或显式手动任务可以重新尝试仍 pending 的条目。重启不自动重放维护，需要下一次信号/手动触发。

源正文/选择/规则改变时旧记录标 stale；实际队列记录与 revision 再次比对后，消费标记、正式双状态、task/log、usage 同事务提交。失败或中止保留 pending，不注入草稿。重复扫描相同原文/规则不会重复替换或重新排队 consumed 条目，不计算内容哈希。变更规则后重扫可能重新提取旧消息，但不会自动撤销之前已写入的正式状态；需要回溯时显式调用历史重建入口。普通聊天助手工具、完整历史影子投影及原生恢复影子队列仍待补；队列导出已进入第十四批全表包。

## 导入导出

| 方法 | 说明 |
|------|------|
| `preflightImport/runImport/cancelImport/observeImportProgress` | 桌面 ZIP / RikkaHub 备份 |
| `startImport(zip, sourceHint=null, sourceLibraryId=null)` | 注册独立 IMPORT 前台任务；保持来源自动合并并存，taskId=batchId |
| `startImport(uri: Uri, sourceHint=null, sourceLibraryId=null)` | 已获读权限的 content URI；接住描述符，再由同一后台 IMPORT 制作私有快照 |
| `getImportTaskProgress(taskId)/observeImportTaskProgress()` | 持久阶段/比例/解包文件数与字节数/inputBytes/committed；不保存原路径、URI、来源正文或密钥 |
| `runImport(zip, sourceHint=null, sourceLibraryId=null)` | 等待同一后台 IMPORT；取消等待不取消 worker，sourceLibraryId 区分同应用多资料库 |
| `runImport(uri: Uri, sourceHint=null, sourceLibraryId=null)` | SAF 来源的相同等待入口；开始后取消等待不取消后台 worker |
| `getImportReport(batchId)` | 持久导入报告，含中断/回滚原因 |
| `listImportedSources/importedSourceRevisions(namespace)` | 来源范围及最新来源 JSON 快照，供冲突追溯 |
| `startBackup(dest, options=BackupOptions(), password=null, overwrite=false)` | 注册 BACKUP 后台导出；仅开启凭据时需独立 CharArray 密码 |
| `getBackupProgress(taskId)/observeBackupProgress()` | 持久阶段/计数/结果与最新内存 StateFlow；数据库扫描中行数仅内存 |
| `exportBackup(dest, options, password=null, overwrite=false)` | 同一任务链的等待兼容入口；取消等待者不取消 worker，显式 stopTask 停止 |
| `preflightNativeBackup(zip)` | 原生 v1 只读格式/schema/路径/容量/文件清单检查，不代表恢复成功 |
| `inspectNativeRestore(zip, password=null, mode=ADD_MISSING)` | 只读内容/引用/当前冲突检查；REPLACE_INCLUDED 仅改变可授权差异分类，不写入 |
| `startNativeRestore(zip, password=null, mode=ADD_MISSING)` | 新 RESTORE 前台任务；默认无冲突追加，显式 REPLACE_INCLUDED 覆盖包内同身份项，不执行迁入旧任务 |
| `getNativeRestoreProgress(taskId)/observeNativeRestoreProgress()` | NativeRestoreProgress 阶段/计数/检查报告/committed；问题项最多 200 且累计 1000000 字节 |
| `restoreNativeBackup(zip, password=null, mode=ADD_MISSING)` | 等待同一 RESTORE worker；取消等待者不取消任务，显式 stopTask 停止 |

导入报告条目状态：`converted|retained|missing|failed|deferred|rolled_back|cancelled|interrupted`。`success` 表示批次提交成功；有 `missing/deferred` 时不表示完整迁移。只读报告不触发重放。

导入与消息多步写入已采用事务；具体合并策略和当前边界见 [STATUS.md](STATUS.md)。原生包走独立 RESTORE，不走来源 importer；`preflightImport` 可返回 `native`，`runImport` 明确拒绝该格式；desktop/Rikka 自动合并并存不改成原生冲突策略。

第十八批来源导入由 core scope 的 ImportTaskService 持有，不依赖页面订阅/等待。startImport 确认前台宿主、拒绝同实例另一活动 IMPORT 后登记；taskId 与 batchId 相同，旧来源 namespace/typed map/三方合并不变。queued/unzipping/merging/done 及终态可查；解包每 500ms 检查点，最多 200000 ZIP 项及实际展开项（含隐式父目录）/8 GiB 实际展开字节，超出失败不截断。附件复制另有 200000 文件/8 GiB 预算，每 65536 字节检查取消、fd 非链接读取/长度与身份检查，完整后同步。保留的 opaque ID 不作路径授权，不是安全叶名时只选新文件叶名，逻辑 ID/映射不变；目标必须在专属 batch 目录内。合并事务内不回调写进度，实体/映射/完成报告与 IMPORT 的 SUCCEEDED/COMPLETED/committed=true 同一 Room transaction。

stopTask、cancelImport、通知停止和宿主中断取消实际 worker；宿主先取消数据事务再等待 STOPPING，第一原因保留。cancelImport 保留旧全局入口；停止 queued 任务也能收尾，不执行旧数据操作。此时可能尚未建批次报告，getImportReport 为 null，持久任务/进度仍有终态；等待入口明确报“批次尚未创建”，不伪造报告。worker 保留宿主 handoff 至私有清理结束。成功只由事务判定，取消恰逢提交返回不会覆盖成功报告或删除已提交附件。

进程重启后若任务已被统一恢复标 INTERRUPTED 而 payload 仍为旧检查点，getImportTaskProgress 以权威任务终态投影 phase/committed=false/时间与错误，保留已有文件/字节计数，不呈现仍运行，不重写报告或续接旧 worker。

清理依据小字段 batchStatus 查询（不加载大报告），无 done 时仅删除本次专属附件树，再清理专属 staging；目录身份/父目录检查、lstat 与单项倒序删除不跟随 symlink 子项，也不递归擦广泛目录。startup 终止旧 running 批次，清理实际私有 staging，done 的附件保留；不因历史失败 batch 无 staging 就删除其目录，不重放源导入。未知身份/IO 错误不假装清理成功，未提交清理不完整锁住 gate 防复用，已提交清理失败仍 SUCCEEDED/done_cleanup_pending。全部大报告/大行、完整真实包与设备宿主仍未验收。

第十九批 startImport(File/Uri) 在返回登记任务前已取得输入描述符。File 只读打开且叶项不跟随链接；之后移走/删除原路径不会迫使 worker 重开路径。Uri 必须为已有读授权的 content URI，使用 AssetFileDescriptor 的实际 offset/declaredLength，支持普通文件、管道和 socket，不猜真实路径。取描述符阶段在 IO，30 秒限时且调用方取消会发 CancellationSignal；此时尚未登记任务，提供方忽略取消可能仍占用 IO 线程，迟到的返回描述符会关闭，不登记任务。真正后台 input 阶段复制后才解压/合并；每 65536 字节检查取消，500ms 持久 inputBytes，快照至多 8 GiB（另有解包/附件各自预算），非阻塞流等待每 25ms 检查取消、连续 60 秒无字节即失败，可靠管道报告错误也拒绝。

输入暂存为 import_inputs/<taskId>/archive.part，0600 文件/0700 任务目录；fd 同步后 rename 为 archive.zip 并同步目录，只有完整输入交给 ZipFile。普通文件前后比较 fd 身份/长度/mtime（API 27+ 纳秒、26 秒）；不以仅路径变化的 ctime 判作正文改变，也不声称能隔离外部恶意写者。输入/解包/导入及收尾共持有数据 shared lease，原描述符复制后关闭。finally/启动仅单项清理归属于 IMPORT 的已知私有文件，不导出这些输入，不恢复旧任务；未知身份/未提交清理失败阻断写入，提交后的输入清理失败保留成功并标 cleanup_pending。停在 input 阶段可以尚无 ImportBatch，报告为 null；任务终态仍保留。这里不长期保留 URI/不擅自申请持久权限，因为进程重启不继续旧导入；新任务需再次提供可读来源。preflightImport 仍为 File 的只读入口，原生恢复输入仍是 File，未声称所有 SAF 场景都接通。

BACKUP/RESTORE 也改用 ATOMIC + 就绪握手和注册期间中断信号；即使首次调度前取消仍进入 finally，清理密码并持久终态，不执行旧数据动作。恢复的宿主 handoff 获取位于 try 内，获取失败仍收尾。两个持久进度读取也按权威非成功终态投影，不把启动中断后的旧 checkpoint 呈现为运行中。其余生成/总结/维护/sandbox 的早停异常仍逐项审计，不能以这两个修正称全任务完成。

描述符获取只允许一个尚未返回的操作。超时不会提前释放仍被提供方占用的获取槽；再次开启来源明确报冲突，不自动重试、不排队堆积后台读取。已取消且尚未调度的获取不调用提供方。该限制不是对忽略取消的提供方线程的强制终止，也不是旧导入续接。

SAF 描述符/片段契约参考 [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) 与 [AssetFileDescriptor](https://developer.android.com/reference/android/content/res/AssetFileDescriptor)，时间字段兼容参考 [StructStat](https://developer.android.com/reference/android/system/StructStat)。这些 API 文档不是设备运行证明。

inspectNativeRestore 会拒绝活动任务/写操作并持有独占 gate。输入只复制到自有 staging；候选库/文件/密码检查在 IO 执行，实际用户库、文件与凭据不变。NativeRestoreInspection 含逐表 insert/identical/conflicting、文件/凭据计数、将终止的旧活动 tasks/summary jobs、将过期的 pending approvals、blocking/warning 数与前 200 个 issue，issuesTruncated 标明省略；只返回记录键/路径/列/错误码，不返回内容/凭据值。过长键/路径不可作为提交授权或缓存 token。不同主键记录/文件/凭据报告冲突，不默认覆盖；未声明本地目录项不构成删除计划。

历史分叉/诊断/已删除来源映射为 warning，必需 live 引用缺失为 blocking。旧非终态任务/总结/审批只在候选库变为 INTERRUPTED/EXPIRED，不启动 worker，不执行工具、导入或模型请求。没有密码但包含凭据时 CREDENTIAL_PASSWORD_REQUIRED；提供密码只在内存比较，调用方负责清空原 CharArray。inspect 为调用方拥有，取消调用即取消检查/清理；它仍不是前台恢复任务。检查结果没有可复用句柄，真正 RESTORE 会重新验证；mode 明确保存在报告和进度中，默认 ADD_MISSING 不变，只有调用方明确选择 REPLACE_INCLUDED 才允许包内覆盖。完整边界与限制见 NATIVE_BACKUP_V1.md。

第三十批 REPLACE_INCLUDED 增加包外保留语音引用扫描：原有效消息 _tts 的资产/冻结选择/来源/原稿证明/助手预设归属/可得账目和声明长度普通缓存字节受保护，受覆盖影响时报 PRESERVED_SPEECH_* 阻断整个检查/恢复，不自动改包外消息或补发语音。旧编辑/淘汰/不可读关系只 warning；已明确包内替换的消息走候选绑定验证。每页 200/至多 100000 行和 64 MiB 文本读，SQL 先限 ID 1024 字节与消息总行 256 KiB，相关 task/usage/cost 逐列 128/64/32 KiB；超整次预算失败，不返回部分成功授权。缓存文件用既有完整字节/模式比较，不哈希；创建来源、完整附件与 asset/requestId 账目也核对，同源分叉/共享不被误判创建消息不同。详细条件/失效警告/不隔离 shell 与全部其余引用限制见 NATIVE_BACKUP_V1.md。

RESTORE 阶段为 queued/input/inspection/staging_files/publishing_files/credentials/database/done。默认不同现有行、文件、模式或凭据报 Conflict，不覆盖全局配置。显式 REPLACE_INCLUDED 只覆盖包内同主键/托管路径/凭据 ID，保留包外行、目录子项和凭据；不清库，不做目录与叶项互换或跨会话/助手身份移动。保留父节点、锚点和有效提取源会失效仍阻断。数据库 INSERT/UPDATE 同事务，rows 为实际插入/更新数；文件保存原 inode 与完整独立字节前提、目录权限撤销意图，凭据保存独立加密 before/after。发布后每项文件/凭据必须一致且存在，提交前再验证。迁入行、native_restore/done marker 与当前成功/committed=true 进度仍同 Room transaction，不用删除型 REPLACE；数据库阶段不回调重入进度事务。

stopTask/宿主中断先取消工作再等待 STOPPING 写事务；未提交就撤销本次新条目、恢复被覆盖叶项/目录权限与旧凭据，提交后保留成功数据。启动查 marker 决定撤销或私有清理，不重新执行旧任务/工具/网络。文件撤销完成有独立 durable 标记，清理中断不会再次撤销；新文件与旧完整字节前提各至多 8 GiB，bytes 为累计，另需归档/候选库空间。未知身份、目录非空或 IO 失败保留日志；未提交的撤销/清理不完整会锁住本进程的普通写与新任务/备份/恢复，直到下次启动恢复处理成功。读取报告仍可用，不提供旧任务 retry/resume。已提交但清理失败为 SUCCEEDED + done_cleanup_pending，下次仅清理。恢复 worker 的 handoff 持有宿主/有界 CPU 租约到清理结束。

原生第十八批按逐段展开链接后再处理 '..' 校核组合图，导出/预检看包内图，内容检查看 incoming+保留本地图，发布后再检查；受影响沙箱的保留链接也不得越界。悬空/非目录/40 次链接解析仍不可达的循环只 warning，保留原链接元数据不跟随。扫描本地至多 200000 项/缓存依赖 200000，超出失败。旧活动任务/总结、pending 审批及 running 导入的终止时间采用包内快照时间与原 created/updated 的较晚者，不随当前检查时间变化；同包重复的候选旧记录保持稳定，不执行旧任务。早期批次按检查时间终止过的记录仍可能差异，需要明确模式，不自动改旧数据。

此链路仅普通编译，没有实际 ZIP/密码/设备/崩溃或断电验收。组合图检查不能隔离之后的恶意 shell/挂载/链接竞争；目录 rename、WAL/偏好与设备 fsync 边界，以及完整引用/版本/幂等性/行为审计继续进行，不能以覆盖模式可编译代替全部恢复需求。

原生格式和所有选项精确定义见 [NATIVE_BACKUP_V1.md](NATIVE_BACKUP_V1.md)。默认包含 schema v2 的 25 张表、来源映射/baseline、记忆/队列/助手历史/任务/用量以及托管附件/沙箱文件，当前导出 task 自身不入包。数据 gate 拒绝其他活动任务和正在写入的操作；持有时新门面写入、任务登记和沙箱修改报 Conflict，不排队重放。数据库一个 Room transaction，文件与凭据受相同 gate；不要调用历史公开 repository/paths 字段绕门面。普通操作彼此仍可并行。

选中本地文件/已登记沙箱缺失明确失败；部分选项会留下包外引用，manifest 明确说明，不能叫完整迁移。无自定义内容哈希，普通 ZIP 仍含用户正文和文件私密信息。默认不包含 CredentialStore；includeCredentials=true 需 12..1024 字符密码，只以 AES-256-GCM 密文包包含，不写密码/密钥到任务 payload。includeRebuildableRuntimes=true 当前 Unsupported，环境清单明确没有 portable rootfs/安装器，不冒充运行时已恢复。

目标不能是活 app 数据或 staging；默认不覆盖已有 ZIP。临时 ZIP 完成/fsync 后默认硬链接原子发布，外部文件系统不支持硬链接时失败，可先导出 app backups 目录再分享；导出显式 overwrite=true 使用 rename 替换选定 ZIP，与恢复的 mode 参数不同。启动清理已登记自有临时文件，保留已发布 ZIP，不重放。结构预检/内容检查/默认追加及显式包内覆盖恢复均已接，全部边界审计仍待补；没有实际设备导出/恢复证据。

## 沙箱

| 方法 | 说明 |
|------|------|
| `createSandbox/listSandboxes/deleteSandbox` | 环境生命周期 |
| `importIntoSandbox(sandboxId, source: File/Uri, relativeTarget)` | 等待同一 FILE_COPY 后台任务，不挂载；取消等待不取消 worker |
| `startFileCopy(sandboxId, source: File/Uri, relativeTarget)` | 独立 FILE_COPY 前台任务，描述符/私有输入快照后发布，不覆盖重名旧文件 |
| `startCopyBetweenSandboxes(sourceSandboxId, sourcePath, targetSandboxId, targetPath)` | 明确跨环境复制独立字节，不共享目录/原 inode；同环境也可显式复制 |
| `getFileCopyProgress(taskId)/observeFileCopyProgress()` | 持久阶段/inputBytes/实际 targetPath/published/publicationReconciled；内存流仅最新一项，不含原外部路径/URI |
| `sandboxListDir/sandboxRead/sandboxWrite/runShell` | 文件与 shell |
| `startSandboxTask(sandboxId, toolName, argumentsJson, chatId?)` | 登记并启动 shell/file 工具后台任务，立即返回 TaskSnapshot；不等待用户审批/执行结束 |
| `startShell(sandboxId, command, timeoutSec=300, chatId?)` | shell 简便入口，同一审批/宿主链路 |
| `getSandboxTaskProgress(taskId)` | 固定参数、approvalSessionId、输出尾部/累计字节/截断和结果元数据 |
| `fileChangesForSandbox(sandboxId, limit=200)` | 含并发时未归单任务的 observed 差异；limit 为 1..2000 |

任务固定 chatId/sandboxId，当前会话切换不会重定向。无 chatId 的独立任务各自有 approvalSessionId；用 `pendingApprovals(progress.approvalSessionId)` 查待审批，仍由 approve/reject 逐项决定。同沙箱、同会话关联的独立工具任务可并行；同任务工具调用保序，审批仍按会话登记顺序逐项批准，文件工具仅对同路径短锁。同会话仍只有一个对话生成任务，独立shell/file任务不冒充第二个生成。模型调用的 call ID 参数/上下文变更报 Conflict，停止或旧进程审批不能放行。

第二十批 FILE_COPY 是用户明确选择来源/目标后的手工复制，不是模型工具绕过审批的新名称。File 或已授权 content URI 先接住描述符，后由 core scope 作私有独立字节快照；来源移走不重开路径、来源后续变化不同步、目标变化不回写。input 每 65536 字节检查取消/500ms 进度，最多 8 GiB；URI 的 offset/length、非阻塞流/取消信号/超时与第十九批来源输入相同。跨沙箱源路径逐段拒绝非目录祖先、叶项非链接只读打开；输入不是跨沙箱 hard link。普通文件保留 owner executable 位（最终 0600/0700），不复制 set-id/其他用户权限，管道来源默认 0600。

目标为非空相对叶路径，拒绝绝对路径/空段/`.`/`..`/冒号/NUL、超过 128 段/8192 UTF-8 字节或单段 255 字节；祖先不能是链接。重名（含同名目录/叶链接）改为同目录新 UUID 叶名，实际路径在进度保存；最终 hard link publication 不覆盖任何已有目标，失败不重试旧任务。该链接仅把本次完整私有快照发布到同一 App 文件系统，然后删除私有别名，不共享外部原文件或其他沙箱文件；跨挂载失败，不偷偷降级为外部共享。

copy_inputs/<taskId> 只保留 input.part/input.bin/publication.part/publication.json，私有根不进原生备份。发布意图为有界 version=1 的任务/沙箱/实际路径/快照 device+inode/字节/时间，先 fd 同步、rename、目录同步，再创建所需父目录/发布目标。父目录变化/CREATED 记录和 FILE_COPY 的 SUCCEEDED/COMPLETED/published/publicationReconciled 在同一 Room transaction；整个短发布决策不可取消，决定前停止拒绝发布，成功提交后的停止或私有清理失败不回滚成功。任务/活动 reservation/handoff 保留至收尾，新备份/恢复不能穿过复制中间态。停止走 stopTask/通知，宿主先取消所有 worker，再写 STOPPING。

这不是 FS 与 Room 的强事务。发布后 DB 提交失败会保留目标与私有意图/输入，锁住写入等待下次启动处理；不删除可能已被用户编辑的目标。启动只比对路径/非链接 inode 并核对元数据：已成功或已 publicationReconciled 的任务只精确清理，未提交且目标仍是本次 inode 则补 CREATION 元数据，缺目标只记录未留发布；旧非终态任务标 INTERRUPTED，不重做复制，不提升为成功。目标换成未知 inode/类型则保留并阻断，不碰陌生文件。元数据核对与 publicationReconciled 同库提交，所以中断于后续清理不会重新拿用户已修改路径作复制授权。停止/失败可留已建空父目录，UNKNOWN/incomplete 记录明确保存；不宣称全量文件观察或断电验收。低层旧 caller-owned importFile 已移除，门面原签名改走同一后台任务。

生成、总结、状态维护 pump/worker 和独立 sandbox 工具也改为 ATOMIC + 就绪握手；WorkerStopSignals 先保留原因并取消，Task/Room 停止写可稍后完成，注册尚未发布 worker 时的宿主原因不会丢。状态 progress 初读移入 try，首次调度前取消仍使用持久配置收尾；宿主同时取消维护 pump，避免等待全库 STOPPING 时继续分派后继。成功事务的 terminalReason 固定 COMPLETED，晚到中断不能生成 SUCCEEDED+HOST_TIMEOUT。其余磁盘/数据库异常、真实进程/设备与全部行预算仍需审计，不等于所有异常已证明。

发布链接语义参考 [Linux link](https://man7.org/linux/man-pages/man2/link.2.html)；这是机制依据，不是 Android 文件系统/崩溃运行证据。

`sandboxWrite`/`runShell` 兼容签名中的 taskId 现在须为未用过的 1..128 位字母/数字/下划线/横线 ID，它们登记审批任务后等待结果，不再直接写文件/启动系统 shell。取消等待者不取消后台 worker。优先用 start 接口和进度查询；停止显式调用 stopTask，重启不重发。默认 custom 审批策略会等待批准，不由这些接口擅自放行。

shell 参数总 JSON 最大 100000 UTF-8 字节，timeoutSec 为 1..3600；先验证独立进程组再放行。stdout/stderr 尾部保留 200000/50000 字节，报告累计量与截断；500ms 检查点和停止最后快照。诊断 JSON 最多 1700000 字节，超限报错而不无限分配。shellOutput 放正文，resultJson 放无重复正文的 ShellResult 元数据；文件结果预览最多 100000 字符且 `resultTextTruncated` 标明。截断 UTF-8 首字符可能显示替代字符，不当作精确文件内容。

read_file/sandboxRead 有限读取 500000 字符，超出附 truncated 标记；write/edit 文本上限 2000000 字符，独立任务参数预算另行约束。所有变更文件工具自动捕获完整字节/结构前提并在路径锁内比较；edit 的 expected 可省略，有值时还需与捕获内容一致。目标删除/创建/内容变化均拒绝，不截断前提放行。write_file 新建归 WRITE、覆盖归 EDIT；create_file 仅新建。删除失败报错，rename 不覆盖目标；目录重命名与子路径工具操作互斥，其他路径并行。sandbox 删除拒绝会话/任务引用和活动操作，不跟随叶链接遍历。无法排除任意系统 shell 换父链接或写同路径的竞争，不能据此宣称真实隔离。

Shell 当前仍为 Android 系统运行环境，不是隔离 rootfs。普通同组子进程与已观察后代会清理，故意脱离/双 fork/设备权限边界未运行证明。文件观察只看至多 20000 个目录项的元数据，不做哈希；重叠差异 taskId=null，需要按沙箱显示。始终保存 UNKNOWN/incomplete 缺口，任务仅保留前 1000 个变化 ID，其他记录仍存库。完整文件事件观察和真实文件/运行环境边界仍待实施。

路径越界抛 `StError.Sandbox`。

## 审批

| 方法 | 说明 |
|------|------|
| `approvalPolicy/updateApprovalPolicy` | 策略 |
| `pendingApprovals/pendingApprovalCount` | FIFO 队列 |
| `approve/reject` | 逐项；禁止批量；批准幂等执行一次 |
| `approvalPremiseManifest(approvalId, offset=0, limit=100)` | 仅存活 PENDING 项的原文件清单，JSON；最多 100 项/页 |
| `approvalPremiseChunk(approvalId, entry, offset=0, limit=65536)` | 原文件字节 Base64；返回 offset/totalBytes/hasMore，不读实时文件 |

未批准前工具无副作用。拒绝以工具结果返回，不换入口重试。

文件调用的 ApprovalRequest.editPremiseJson 现为 version=1/comparison=exact_bytes 的结构化前提清单，默认前 10 项，manifestTruncated 表示需分页；entry 索引用于字节读取。只对待审批且任务存活的调用提供内容，批准/拒绝/停止后失效。原文件完整副本在私有托管临时目录，仅用于这次调用；结束清理、进程启动清理遗留，不恢复旧审批。前提最多 20000 项/256 MiB，超过失败；不计算内容哈希。参数最多 1000000 UTF-8 字节（独立 sandbox task 更严格为 100000），不会把全部文件内容塞进审批行。

ApprovalStore 入库检查审批行所有字符串字段的 UTF-8 总量最多 1700000 字节，超限抛 Validation，不生成超大审批行；其他业务表的大行仍按完整审计单继续处理。

审批后前提变化：调用报 StError.Conflict，批准项标 SUPERSEDED，不自动放行或再弹同一调用。失败/过期 call ID 不可再次尝试，必须新调用；成功/拒绝结果按原身份幂等。自动模式也检查前提。捕获/比较可取消，有限文件发布/变更记录在 NonCancellable 范围完成；已发生修改不回滚。文件工具原始修改方法也要求显式完整前提；UI 仍须走 core 门面，不自行创建前提绕审批。

## 任务

| 方法 | 说明 |
|------|------|
| `getTask/recentTasks` | 状态机见 `TaskStatus` |
| `fileChangesForTask` | 文件变更；`incomplete=true` 表示记录不完整 |
| `fileChangesPage(sandboxId, cursor=null, limit=100, throughSequence=null)` | 1..200 项的有界预览，返回 nextCursor/throughSequence/hasMore/truncatedFields；重启/原生恢复旧 cursor 明确 Conflict |

shell 的 FileObservationReport 给出共享采集窗口/计数/缺口，complete 始终为 false。原始事件与元数据差异 taskId=null，不把窗口归属当写入作者；移动通知 UNKNOWN，不猜配对。分页预览不可用于文件操作；后续传回初次 throughSequence 可固定上界，cursor 不是备份可移植序号或旧任务续接入口。停止时结果可能无法返回，起止 UNKNOWN 行仍在收尾尝试持久化。详情及预算见 [SANDBOX.md#sandbox-file-observation](SANDBOX.md#sandbox-file-observation)；旧全文接口保留但不推荐长记录读取。

状态：`QUEUED|RUNNING|AWAITING_APPROVAL|STOPPING|SUCCEEDED|FAILED|STOPPED|INTERRUPTED`。

## 事件顺序（生成）

1. 可选写入 user 消息节点，构建上下文。
2. 在同一事务内创建活动 assistant 候选及其任务引用；每 500ms 保存正文、思考、usage 和运行元数据，结束时立即保存。
3. `Delta/Usage` 是临时通知；无订阅者也照常保存。上游 Done 不直接转发。
4. 完整 ToolCall 保存为 assistant.toolCallsJson；若需审批进入 `AWAITING_APPROVAL`，逐个保存 tool 结果并回传下一轮请求。不完整调用留在历史中，但后续上下文不会重放，完整调用/结果整体裁剪。
5. 每次模型请求账目已独立收尾；成功、失败或停止时将最后回答（附 usage/已保存费用快照）及任务终态一并提交。若活动回答已被本地编辑，停止生成并保留编辑；若删除则不重建，不撤销供方账目。
6. 事务提交后发送一次最终 Done（仅成功时 ok=true）；失败先发送 Error，再 Done(false)。停止发送 Done(false)，状态从 getTask 读取。HTTP 错误、截断、断流或安全拦截不标成功。

重订阅只恢复展示，不重放工具。

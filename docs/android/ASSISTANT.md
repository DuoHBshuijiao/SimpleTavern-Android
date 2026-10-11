# 助手与工具

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="assistant-chat"></a>

## 独立助手对话与工具

2026-10-10 路径说明：RikkaHub普通助手/会话仍映射为CharacterCard+普通ChatSession，systemPrompt、chatDefaults、模型、世界书及来源记忆沿普通上下文；原prepareSourceTools也接来源记忆、会话引用、local/skills/MCP工具。名称叫“assistant”不意味着每个会话都要绑定Android独立助手运行时；只有明确的overrides.assistant走本页专用路径。有限核对未证明缺该绑定是继续聊天阻塞，因此不强制新增绑定或工具权限；不声称全部源工具/版本已等价。

2026-10-10 可选配置：`listAssistantConfigs()` 现排除来源映射为 source_settings/source_memory 的共享容器；它们仍由原底层读取、导入/备份和全局/记忆业务保留，不删除或转换为另一类数据。独立助手创建、准备请求、预览和上下文统一拒绝把这些容器当作助手配置；本地 default 的明确创建行为不变。Luna 修改原 AssistantService.configuration，主代理接原来源映射判断与 Core 列表，不新增类型表、通用校验或引用扫描。近期会话的无时区排序适配见 [CHAT.md#conversation-reference](CHAT.md#conversation-reference)。

2026-10-10 配置删除：新增 `deleteAssistantConfig(id, expectedUpdatedAt, deleteRelatedChats=true)`，默认沿原聊天删除流程清除该配置的独立会话，返回删除 ID；传 false 可拒绝连带删除，绑定主聊天保留。会话/语音活动任务冲突，不自动停止。RikkaHub 成对角色须先明确删除，共享来源设置/记忆库不作为单助手删除；文件、技能、凭据与历史账本保留，见 [CHAT.md#library-removal](CHAT.md#library-removal)。

2026-10-08 历史引用：enableRecentChatsReference 启用 recent_chats/conversation_search/conversation_read；普通来源角色也使用相同工具。近期列表限自身助手，搜索/读取可查同导入来源其他助手历史，不借用其他 namespace；不自动注入主聊天，不重放旧工具。与 enableMemory 独立，详见 [CHAT.md#conversation-reference](CHAT.md#conversation-reference)。

2026-10-08 共享记忆工具：memory_list 和 memory_tool 接自身来源配置选择的池，不是绑定主聊天总结。allowWriteMemory 控制新增/编辑，allowDestructiveTools 额外控制删除，仍走原审批；普通来源角色聊天也接工具。同批补齐助手正则工具的六个新字段，详见 [ASSISTANT.md#assistant-memory](ASSISTANT.md#assistant-memory)。

2026-10-08 正则：独立助手读取自身 canonical contentRegexRules/会话覆盖，不自动使用普通聊天全局规则库。请求与工具续轮只读投影，仅显示规则/思考可通过消息预览读取；工具续轮的新助手正文使用原时间模板，原参数/附件/工具结果不改。助手规则工具支持 engine/roles/visualOnly 等字段，旧工具记录不执行，实际 UI 与原生请求仍待验收，见 [CHAT.md#content-regex](CHAT.md#content-regex)。

2026-10-07 记忆：RikkaHub 独立助手按自身配置读取同来源个人或 __global__ 记忆，不是读取绑定主聊天的总结；enableMemory=false 不注入，useGlobalMemory=true 不同时注入个人池。公开上下文 injectedMemory 与实际注入一致，原任务/工具权限不变，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 创建：新助手会话复制已转换的配置预设消息，使用新节点/候选 ID 并清除可执行旧工具字段；附件沿用托管引用，不重放工具。请求模板由会话覆盖或配置提供，使用历史原时间，只加工历史正文，不自动引入主聊天/世界书/记忆状态。原工具绑定/审批不变，详见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 后续：助手原200节点窗口移除，读全部选中候选；会话contextMessageLimit为null继承助手配置，显式0关闭，正值阶梯裁剪并保留工具组。主聊天世界书工具新增chat_worldbook_selection_set和模式读取，源会话覆盖不再强行并集；本文旧窗口描述为历史，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

主线实施：2026-10-06 至 2026-10-07。已接代码、导入转换与普通编译；没有实际账号/模型/导入/设备执行，不表示完整助手或完整后端已经验收。

### 使用入口

- `createAssistantChat(binding=AssistantChatBinding("default"), title="聊天助手", sandboxId=null)` 创建独立历史。默认本地助手配置不存在时只在创建时生成空配置；它沿用本地全局连接，不借用其他来源配置。
- binding 包含 assistantId、可选 targetChatId，以及默认关闭的 allowWriteMemory / allowWriteState / allowDestructiveTools / sandboxToolsEnabled。目标为普通主聊天；沙箱工具需要明确选择 sandboxId。
- `listAssistantChats(assistantId?)` 查询助手会话；`startAssistantGeneration(chatId, userText, provider?, webSearch, attachmentIds)` 发起新生成。原 `startGeneration` 也识别助手绑定；助手不参与群聊轮次或角色插话。
- 历史分页、候选编辑/分叉、附件托管、`observeGeneration`、`stopTask` 和逐项批准/拒绝继续使用原 Core API。绑定保存在 overridesJson 的 typed assistant 字段，无 Room schema/版本改动；不创建另一套任务宿主或 agent loop。
- `previewProvider` / `buildContext` 返回助手自己的配置/上下文。提示由内置助手提示或明确 systemPrompt 提供；桌面已弃用 prompt 仅保留，不重新启用。

### 配置、上下文、任务

助手配置的 presetId / model / temperature / reasoning / Fast 与会话覆盖接现有四协议 ProviderResolver；会话明确覆盖优先。原生缓存操作与后台入口的当前连接核对也识别同一助手配置，不把它重新解析成普通角色会话。旧来源 presetId 按同 sourceNamespace + sourceId 精确解析，RikkaHub chatModelId 对应 model 预设；缺失/歧义不取另一来源的第一个模型。

maxToolTurns 默认 8，支持 1..64 个模型轮次；最后一轮只能给最终回答，继续请求工具会终止，不再执行。maxToolsPerTurn 可设 1..128，超出的调用返回 LIMIT_EXCEEDED 且不执行。chat_generation 用量轮次范围同步为 64，其他维护用途仍为 8。每轮仍由既有 prepared/running/terminal 用量记录管理；旧任务/旧审批不续接、不重放。

上下文只包括系统提示和独立助手历史，最近至多 200 个选中消息；完整工具 call/result 组一起裁剪，不重放历史调用。context_size 支持 0..1000000，0 关闭估算 token 裁剪但不取消历史窗口；估算 chars/4，不宣称精确 token。主聊天、角色叙事 prompt、记忆/MVU/KG 不自动注入，助手需用明确工具读取。纯附件消息现已保留，用户媒体仍走既有托管/四协议输入链路。

助手与普通生成共用 CHAT_GENERATION、增量保存、前台/停止/启动中断处理与四协议工具往返。助手不会自动触发普通角色聊天的总结/MVU 维护；明确工具写入是独立、已批准的副作用，停止不会自动撤销已经完成的写入。

### 当前工具

- 无主聊天也可用：core_get_time；worldbook_list / worldbook_get / worldbook_create / worldbook_update_meta / worldbook_entry_add / worldbook_entry_update。条目删除还需 allowDestructiveTools。
- 绑定主聊天后：chat_read_conversation / chat_read_long_term_memory / chat_list_participants / chat_read_character_card；角色读取限主聊天参与者。
- 内容编辑补齐（2026-10-07）：worldbook_delete 整书删除并同事务清理角色挂载、聊天挂载和全局排除；保留来源映射/合并基线，后续来源三方合并不会因这次删除重建旧书。整书和条目删除都需 allowDestructiveTools。worldbook_update_meta 增 sessionChatIds，条目增改支持 orderIndex。
- 世界书绑定：chat_get_worldbook_state / chat_summarize_active_worldbooks；chat_worldbook_global_exclusion_set / chat_worldbook_attachment_add / chat_worldbook_attachment_update / chat_worldbook_attachment_remove / chat_worldbook_attachment_reorder。写入用读取状态的 expectedUpdatedAt，返回新状态；重排保留未列出的尾部顺序，不删除资料库书。
- 正文规则：chat_content_regex_list / character_content_regex_list 为只读；桌面同名 chat_content_regex_manage / character_content_regex_manage 支持 list/upsert/delete，写入增加 expectedRules 完整字段前提。upsert 的 rule 是字段补丁，已有 id 必须存在，新规则不传 id；delete 用 rule_id。仍由原 MemoryService 校验 RE2 子集并保存/投影，不改写消息原文。角色作用域固定为主聊天主角色，不任意选择别的角色；global 同 ID 优先、聊天已设规则不再回落角色的原行为不变。
- chat_patch_character_card 修改绑定主聊天参与角色的 name/description/personality/scenario/firstMessage/exampleDialogue/systemPrompt，未传字段不变；保留 ID、来源、头像、规则和其他字段。审批提示明确这是资料库修改，影响所有使用该角色的聊天，不是桌面 workspace_patch_character_card 的隔离草稿/发布流程。使用 expectedUpdatedAt。
- 记忆：allowWriteMemory 才声明 chat_append_long_term_memory；覆盖再需 allowDestructiveTools 和 expectedVersion。追加、覆盖用原 MemoryService/Room 事务，不覆盖别的聊天。
- 状态读取：mvu_get_chat_context / read_mvu_logs / kg_get_context / kg_query。allowWriteState 才声明 mvu_set_cell / patch_state_variable / kg_upsert_entity / kg_upsert_relation；kg_delete_entity 再需破坏性开关，采用原图谱软删除和关系清理。
- 沙箱工具：明确开启后声明 shell / read_file / list_dir / create_file / edit_file；删除/重命名专用工具再需破坏性开关。shell 本身是完整 guest 命令能力，不以命令黑名单模拟语义权限；该开关不限制 shell 内命令的文件副作用。沿用原沙箱路径边界、文件编辑前提、分类审批和 PRoot 运行器。
- 搜索：仍须本次 WebSearchOptions 明确启用；默认绑定助手自身搜索配置，不因来源内多个助手而选错配置。原生/独立分流、费用记录和缺配置错误不变。

所有调用经过原 ToolRuntime 排队、调用 ID/参数绑定和审批。非文件编辑使用已读取版本：记忆/状态/图谱 expectedVersion，世界书 expectedUpdatedAt；执行时原事务比较，冲突不覆盖。世界书当前只比较更新时间，调用方不更新时间的同时间戳编辑仍可能漏检，并非完整内容前提。修改助手绑定或权限后，旧计划下一次调用拒绝；已经运行的调用须明确停止，不把权限编辑声称为撤销。

主聊天读取支持 transcript / full（同 transcript）/ since_memory_marker、afterOrder/limit 分页；每页最多 200，默认 100，受 tool_read_max_messages / tool_read_max_tokens 约束并返回 nextAfterOrder/hasMore。正文页约 800000 UTF-8 字节，工具输出上限 1000000；单条太大明确拒绝，不用无游标截断声称完整读取。状态/图谱写入返回紧凑版本/ID，不把整份状态再复制进工具历史。

### 世界书运行语义（2026-10-07）

挂载按聊天列表顺序、再角色挂载顺序、最后其他激活书，去重；摘要与上下文共用选择方法。来源全局书只在同来源聊天生效，本地全局书沿用可跨来源行为。globalActive 书被全局排除时不再因 sessionChatIds 激活；非全局书沿用 sessionChatIds 激活。Android 保留显式聊天/角色挂载可直接激活的行为，因此显式挂载不被“全局排除”取消；这与桌面当前只用激活索引、挂载仅排序的实现并非完全相同，不宣称逐项一致。群聊生成按当前出场角色的挂载，摘要按主角色。

扫描默认读取本聊天的精确来源设置 worldBookEntryScanDepthDefault，缺值默认2；全局值0表示所有 enabled 条目，不看触发词。挂载 scanDepth 不设继承默认，工具传0重置，显式深度1..500。非零扫描用多行、区分大小写正则，空正则不触发，坏正则跳过并记录诊断。2026-10-10对照当前 ContextBuilder 源码确认已读取全部选中消息，不再有旧最近80节点硬窗口；触发仍受用户锚点、contextMessageLimit及最终上下文预算裁剪，不是无条件检索全部被排除的历史。Kotlin 正则也不等同桌面的完整 Python regex 能力。

insertDepth 从保留历史末尾向前计数，默认5；同位置按书顺序插入 system，不再仅用于排序并全部放入首条系统提示。插入点落在 tool 结果内时移动到该 assistant 调用之前，保留整组。先预留注入的 chars/4 估算成本裁剪整组历史，再按最终历史重匹配；被裁掉的旧触发正文不激活最终世界书。标题及内容计入估算，仍非精确 tokenizer/严格 token 硬上限。

世界书、绑定、角色的时间戳前提仍有原本“调用方未更新时间”的漏检限制；正文规则使用完整已读规则列表比较。内容编辑沿用现有分类审批，不新增权限系统/表结构/后台重放，停止不撤销已完成编辑。

### 导入与恢复

桌面全局 assistant_chat.json 和 chats/**/assistant_chat.json 转为独立助手 ChatSession/消息节点，后者在可读 chat.json 中有主聊天 ID 时绑定相应主聊天；缺 ID 时按原包相对路径保留为各自的未绑定历史，不猜测主聊天或合成同一份全局历史。助手/节点/候选有独立来源映射，原 JSON 继续保留；使用原 ImportMerge 三方合并、并存和本地编辑保护，重复导入不清库/拼接别的来源历史。源普通会话中的未知 assistant 字段不转换为 Android 权限，导入生成的助手绑定默认不开放写入/破坏性/沙箱权限。助手预设 ID 在新导入中重映射；RikkaHub 助手配置增已知 controls 和选中 model 预设，原角色/会话映射不变。

2026-10-07 后续已接隔离工作区文件、卡草稿/明确发布，以及 ai_workspace 文本/资产/助手历史和消息 ingest 附件转换；不再把整棵工作区统一 deferred。逐项缺失或未知存储引用仍报告并保留 metadata，完整格式/来源布局/二进制修订和 native 关联尚未完成，见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。既有 assistant_messages 和旧 source_blobs 不在启动时搬动，需明确再次导入执行新转换；旧工具记录只作历史。

原 native 备份覆盖配置、聊天和消息；恢复预检增加助手配置/目标聊天引用以及自指/群聊/缺沙箱绑定检查。完整包外绑定覆盖保护、所有历史关联/大行、动态文件链接和实际恢复仍待审计，不能据基本引用检查宣称完整恢复验收。

### 仍需补齐

完整来源工作区布局/二进制修订、工作区到沙箱明确复制/导出、完整 Python regex 兼容/全历史与 tokenizer、所有 MVU/KG 来源行为、非流式行为、完整历史附件转换、全部 native 关联、设备/上游/审批并发证据。整书删除仅清理当前 typed 业务引用，未做所有未知原始来源字段/native 包外关联审计；不能据此声称完整恢复已验收。上述目标仍保留；本轮优先可用主链路，不展开缓存费用细项或延期内部防御清理，没有哈希、冒烟或新增单测。

<a id="assistant-local-tools"></a>

## 来源助手内置工具

2026-10-08：RikkaHub `localTools` 中 `{"type":"javascript_engine"}` 和 `{"type":"time_info"}` 已接普通角色、群聊实际生成成员及独立助手。记忆/近期聊天开关不决定这两项工具是否安装，沿用原工具参数验证、审批分类、结果持久化、续轮和停止链路；不开新任务引擎或数据库表。配置缺失/未选中不安装工具，审批后配置已禁用时拒绝该调用，导入本身不执行任何脚本。

- `eval_javascript(code)`：每次独立 Rhino 解释器，返回最后表达式的字符串或对象 JSON，null/undefined 返回 null；捕获 console.log/info/warn/error/debug。源代码最多 256000 字符，结果最多 128000 字符，日志最多 16000 字符并标记截断；脚本错误带 error 返回工具失败。
- `get_time_info()`：返回 year/month/day、当地及英文星期、ISO 周序号、date/time/datetime、timezone/utc_offset 和 timestamp_ms。时间取实际调用时设备时区，不改会话原消息时间。

### 引擎适配与边界

实现使用 `org.mozilla:rhino:1.8.1`，Android 解释模式、ES6 语言档及该版本支持的扩展。没有 DOM、Node、Java 类型、文件、网络、外部模块或异步宿主接口，不宣称完整 QuickJS/ES2020 兼容。初始化 safe standard objects、禁止 Java 类访问，不把仓库、沙箱或应用对象传入脚本；仅注入接收已格式化字符串的日志函数。

通过解释器指令观察点检查调用协程取消及 10 秒单调时钟截止，解释栈深度设 128；错误、返回值及日志格式化也在解释器内完成。它不是独立进程：没有 QuickJS 式堆内存硬上限；解析、正则/字符串等引擎原生计算不保证每 10 秒准时中止。若要求敌意脚本的硬 CPU/内存隔离，仍需专用执行进程或兼容版本的原生引擎，不把协作截止描述为完整隔离。

选择原因：已核对上游 QuickJS 1.0.15 的官方 Maven 发布依赖为 Kotlin 2.4.10 / coroutines 1.11.0；本工程为 Kotlin 2.0.21 / coroutines 1.9.0。本批先接可用工具，不为这一项升级全部 Kotlin/KSP/Room 编译链。依赖由正常构建下载，没有跳过 Kotlin 元数据检查、额外单测或脚本冒烟执行。

### 仍待补齐

后续同日 MCP 两种 HTTP 传输的连接、发现、调用与配置已接，剩余 stdio/OAuth/资源/媒体项见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)；下列内置工具仍未完成。

同日 `ask_user` 已补 typed 问题、文字/单选/多选答案提交、待处理队列观察与原生成续轮，复用 FIFO/等待/取消而不以布尔审批代答，见 [ASSISTANT.md#user-questions](ASSISTANT.md#user-questions)。输入界面本批不改，真实交互未验收。

同日 `clipboard` 已接 `clipboard_tool(action, text?)`，覆盖普通角色、群成员和独立助手。`read` 属 READ；`write` 属 WRITE，按原自动/自定义写入策略审批，模型描述要求用户明确请求才写入。主线程调用 Android ClipboardManager，只读首项已有纯文本，不转换 URI/Intent 或打开外部文件；写入 ClipData.newPlainText，读写最多 256000 UTF-8 字节，不添加权限或持续监听。系统没有提供文本时返回 `text:null, available:false` 并说明可能为空、非文本或后台/焦点限制；SecurityException 返回工具失败，不声称后台可随时读取。结果接原工具记录/续轮，没有直接读取过设备剪贴板。

同日 `calendar` / `screen_time` 已接实际 Calendar Provider/使用事件，安装 calendar_query/calendar_create/get_screen_time，权限 DTO 与声明同步；日历创建始终逐项确认，缺权限不自动申请，见 [ASSISTANT.md#device-tools](ASSISTANT.md#device-tools)。真实设备、授权前端与统计完整性未验收。

同日 `tts` 已安装 `text_to_speech(text)`，系统朗读/已有云端合成后播放接实际 Android 输出和原后台任务，返回独立播放 task_id；RikkaHub System/MiniMax 与明确原有云端预设可路由，不把合成或排队当作播放完成。其他来源语音提供方、非1默认播放速度及媒体参数仍缺，明确失败/提示而非静默切系统，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。MCP/skills 的剩余项、完整来源工作区与媒体兼容仍属于后端余项。未执行用户脚本、真机操作、语音或真实模型调用，编译不代表运行验收。

剪贴板依据：[固定 RikkaHub ClipboardTool.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ClipboardTool.kt)、[Android 复制粘贴](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)、[Android 剪贴板访问限制](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)。

接口依据：[固定 RikkaHub LocalTools](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt)、[QuickJS 1.0.15 Maven POM](https://repo.maven.apache.org/maven2/io/github/dokar3/quickjs-kt/1.0.15/quickjs-kt-1.0.15.pom)、[Rhino 官方发行](https://github.com/mozilla/rhino/releases)、[Rhino ContextFactory 源码](https://github.com/mozilla/rhino/blob/Rhino1_8_1_Release/rhino/src/main/java/org/mozilla/javascript/ContextFactory.java)。来源文件不是本工程助手的执行指令。

<a id="assistant-memory"></a>

## 助手共享记忆

2026-10-08。后端接口和工具接线；实际模型、资料导入及设备运行未执行。

### Core 接口

- `getAssistantMemoryPool(assistantId)`：当前个人或全局池，含 assistantId、sourceNamespace、ownerId、enabled；非 RikkaHub 来源返回 null。
- `listAssistantMemories(assistantId, afterId="", limit=100)`：按字符串 ID 排序分页，limit 为 1..200，返回 pool、entries、hasMore、nextAfterId。
- `getAssistantMemory(assistantId, memoryId)`：读取当前池单条 id/content。
- `createAssistantMemory(pool, text)`：返回新行；本地 ID 为 `local:UUID`，不冒充来源整数 ID。
- `updateAssistantMemory(pool, memoryId, text, expectedContent)`、`deleteAssistantMemory(pool, memoryId, expectedContent)`：比较读取时的完整原文，冲突后重新读取；不使用哈希。

写入前取得 pool。操作只在相同来源和 owner 内进行，个人与 `__global__` 不混合；切换池后旧 pool 不能继续写。管理接口可操作禁用的池，enabled 只控制请求注入和模型工具。空正文不能新增/修改。

数据仍存于已存在的来源 `source_memory` 配置，沿用 Room 事务和普通写入门禁；无新表或后台任务。配置阶段没有记忆库时首次新增会创建库；已映射但被删除的整库不会悄悄重建。

### 聊天工具

启用记忆的普通来源角色聊天与独立助手使用同一组工具：

- `memory_list(afterId?, limit?)` 返回当前池的分页 ID 与原文。
- `memory_tool(action, id?, content?, expectedContent?)` 支持 create/edit/delete。create 需要 content；edit 需要 id、content、expectedContent；delete 需要 id、expectedContent。

新增沿用 WRITE 审批，编辑/删除沿用 EDIT 审批，无沙箱要求。普通聊天仅使用同来源当前角色的配置；群聊按实际发言角色。独立助手默认只读，写入需 allowWriteMemory，删除还需 allowDestructiveTools；不借用绑定主聊天的记忆池。配置禁用或切换池后旧请求的记忆工具不继续操作；工具结果进入原聊天工具续轮，不启动另一套 agent loop。

这是来源能力的适配接口，不是参数逐字兼容：RikkaHub 2.5.5 的 memory_tool 使用整数 id，本地接口统一使用字符串 ID，并增加读取原文的分页工具和 expectedContent。原型依据固定官方 [MemoryTools.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt) 和 [ChatToolFactory.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt)。历史工具记录不重放。

### 再导入与范围

来源行 ID 不变；再次导入继续逐行/字段合并，保留本地新增、修改、删除及来源省略的已有资料。只有来源配置、没有数据库时新建的本地库，也能在首次数据库导入时合入来源行；没有基线不再整库挡住导入。删除行保留原导入基线，因此再导入不复活本地删除。

此接口是 RikkaHub 来源共享记忆，不替代普通聊天的长期总结，也不新增 Desktop 的虚构个人/全局池。前端管理界面、实际模型调用和完整 native 恢复仍未验收；不表示全部后端完成。

<a id="assistant-workspace"></a>

## 助手工作区与桌面文件导入

2026-10-09 写出复用：前一批沙箱SAF复制提取为SafDocumentOutput，原入口/进度/停止/8 GiB规则不变；原生备份URI输出共用同一provider写出/关闭/取消实现，实际写出时相同URI字面值跨两种任务互斥，不增任务框架或自动重放。备份流程详见 [NATIVE_BACKUP_V1.md](NATIVE_BACKUP_V1.md)。

2026-10-09 SAF：新增沙箱文件直接导出到调用方已获授权的文档content URI，沿同一个FILE_EXPORT后台任务，不猜文件系统路径、不自动取权限/重试、不删除失败的用户文档。原“直接SAF导出尚缺”是历史状态；设备/提供方实际效果、孤儿清理和完整native仍待补。

### 导出到用户文档（SAF）

`startExportSandboxFileToDocument(sandboxId, sourcePath, destination: Uri)` 返回 FILE_EXPORT TaskSnapshot，使用原getSandboxExportProgress/observeSandboxExportProgress/observeTasks/stopTask。宿主先通过用户文档选择取得可写content URI，通常来自ACTION_CREATE_DOCUMENT；后端不启动选择器、不自动持久化URI授权，也不在重启后续写。

这个明确写出操作以 `wt` 打开所选目标、替换其原字节，调用方不能把“挑选已有文档”和“不覆盖”混为一谈。原沙箱普通文件描述符先打开；按64 KiB复制，最多8 GiB，允许空文件，不受模型媒体的256 MiB限制，不读取整个文件进内存或增加私有中间副本。源文件EOF大小/时间检查仍保留；若写出中源被shell改变，任务失败而文档可能已有部分字节。

进度 `documentUri` 记录所选目标；`documentMayBePartial` 在打开可能截断的输出前持久置true，写出并正常关闭后为false；`documentWriteCompleted` 表示本地客户端完成了EOF读取、写出/flush及正常关闭，不代表云服务已耐久上传。打开失败也可能影响文档，因此这两个标记不把“字节计数0”当作目标未改变。普通内部托管导出保持原Room同事务提交，SAF外部字节不能与Room一起原子提交或回滚。

停止会取消provider打开信号，并尝试通过错误关闭描述符打断在途输出；结构化协程保持原租约直到写出和取消观察协程结束。提供方可能忽略取消或长时间阻塞，不能保证通知停止瞬间释放。若输出已正常关闭、但停止抢先于任务成功提交，终态仍可能为stopped/interrupted而documentWriteCompleted=true；不能据此自动再次写出。进程死亡后的未知写出保留不确定标记，只中断旧任务，不重新打开该URI。

同一进程中，相同URI字面值的并行导出拒绝，其他文档可并行；不假称能识别所有provider URI别名。无模式fallback/自动重发、readback或哈希校验；失败/停止不自动删除目标，也不尝试恢复旧字节，用户自行检查或移除部分文档。未运行用户文件、文档提供方或设备；仅普通源码检查与Debug编译，没有新增表、依赖、前端、冒烟或单测。

官方接口边界见 [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) 与 [AutoCloseOutputStream](https://developer.android.com/reference/android/os/ParcelFileDescriptor.AutoCloseOutputStream)；提供方的模式、管道和关闭行为仍需实际设备证据，不以编译推定。

2026-10-09 后台导出：文本、工作区资产和聊天附件新增独立 FILE_EXPORT 启动接口，沿原任务宿主、通知停止、进度查询及平台中断；原同步接口保留。原下文“没有 export taskId”只适用于同步接口。直接 SAF 导出、孤儿清理、完整 native 和实际设备仍待补。

### 沙箱产物后台导出

- `startExportSandboxTextToAssistantWorkspace(sandboxId, sourcePath, assistantId, workspacePath, expectedContent=null)`：读取完整 UTF-8 文本并写入工作区，沿原 256 KiB 限制和全文替换前提；空文件允许。
- `startExportSandboxFileToAssistantWorkspace(sandboxId, sourcePath, assistantId, workspacePath, mimeType="application/octet-stream", expectedAttachmentId=null)`：流式复制到托管附件并关联工作区资产，沿原 8 GiB 上限和旧附件 ID 比较；空文件允许。
- `startExportSandboxFileToChatAttachment(sandboxId, sourcePath, chatId, filename?, mimeType="application/octet-stream")`：复制为新聊天托管附件，沿当前 256 MiB 上限、非空规则，不自动追加消息或发给模型。

三项返回普通 TaskSnapshot；使用 `getSandboxExportProgress(taskId)`、`observeSandboxExportProgress()`、`observeTasks()` 和 `stopTask(taskId)`。进度保存目标类型、原 sandboxId/路径、assistantId/chatId/工作区路径、复制字节数、阶段、结果附件或工作区更新时间；不重复保存文本正文。观察流是本服务最近一项的状态，指定任务的持久状态用查询取得。

任务从排队到收尾持有原沙箱操作租约，防止该环境被删除或被原生快照/恢复占用；不阻止同沙箱 shell 并行。实际读取仍用原普通文件描述符与 EOF 大小/时间比较，不新增哈希，也不把最佳努力文件检查说成内核隔离。输入从工作协程读取时开始复制，不承诺启动接口返回时已冻结源字节。

字节读取可取消，进度逐块观察、持久检查点最多每约500毫秒一次。文本/资产引用、附件登记和任务成功状态同一 Room 事务提交；提交前的停止/失败保留旧目标并清理本次私有副本，成功提交后保留结果，竞态停止不撤销已完成发布。任务不修改沙箱源文件，因此不新增源文件变化事件。重启沿通用任务恢复标为 interrupted，不续接或重放旧导出；进程死亡前未登记的孤儿文件精确清理仍未实现。

这批由 gpt-6-luna 编写模型/Core/通知接线，主代理实现复制、提交、停止并检查合并。没有新增表、依赖、审批队列或另一套任务引擎；只做正常编译和代码检查，未运行用户文件/设备，没有新增单测、冒烟或哈希校验。

2026-10-08 导出：沙箱文件可明确保存为工作区 UTF-8 文本、工作区二进制资产或聊天托管附件；资产关联/删除 Core 和助手工具同步补齐。不是自动执行或挂载，也不宣称长期导出任务宿主已完成。

### 沙箱产物导出与资产管理

- `exportSandboxTextToAssistantWorkspace(sandboxId, sourcePath, assistantId, workspacePath, expectedContent=null)`：完整 UTF-8 文本，最多 256 KiB；超大或无效 UTF-8 报错，不截断。空文本允许；替换需要已读完整 expectedContent，null 仅新建。
- `exportSandboxFileToChatAttachment(sandboxId, sourcePath, chatId, filename?, mimeType="application/octet-stream")`：复制为新托管附件，返回 AttachmentRef，不自动修改候选消息或发给模型。沿当前聊天附件规则最多 256 MiB、非空；在新请求显式选择返回的 attachment.id 后仍受既有媒体能力/格式限制。
- `exportSandboxFileToAssistantWorkspace(sandboxId, sourcePath, assistantId, workspacePath, mimeType="application/octet-stream", expectedAttachmentId=null)`：复制为新资产，最大 8 GiB，允许空文件；大文件不先载入内存，也不冒充模型可直接读取。附件登记与资产引用在同一 Room 事务提交；已有目标需要原附件 ID，冲突时保留旧资产并清理本次未提交的私有副本。
- `linkAssistantWorkspaceAsset(assistantId, path, attachmentId, expectedAttachmentId=null)`：将已注册附件关联为资产；null 仅新建，已有引用以旧附件 ID 比较，不做哈希。保留已存在资产的来源标记。
- `deleteAssistantWorkspaceAsset(assistantId, path, expectedAttachmentId)`：只删除工作区索引，保留附件登记、真实字节及来源合并基线，不破坏可能由其他聊天/工作区使用的文件；后续附件垃圾回收仍待补。

独立助手有 `workspace_link_asset`（EDIT 审批），`workspace_delete_asset` 另需 allowDestructiveTools；都固定当前 assistantId，删除工具明确返回字节保留。导出是 Core 的用户显式操作，不新增自动读其他沙箱或绕过审批的模型导出工具。

来源使用指定沙箱相对路径、普通文件的打开描述符，读到 EOF 后比较大小与修改时间，复制期间文件变化则失败，不保存已发现变化的混合结果；不使用哈希。保留原沙箱操作租约，不阻止同沙箱其他任务并发，不将最佳努力描述符/父路径检查冒充恶意 shell 内核隔离。产物是独立副本；后续沙箱、工作区或附件修改不互相回写。

上述原同步接口是调用方协程可取消的 suspend 操作，IO 在后台调度，本身没有 export taskId/通知停止；需独立后台生命周期时使用本页新 startExport 接口。两类接口都不重启续接；提交成功的附件/索引保留，提交前的普通失败/取消清理本次副本，进程死亡前未提交的孤儿文件清理仍待补。超大文件长期后台/进程死亡行为、直接 SAF 导出、整目录和完整 native 包外引用仍未验收；不能以本批编译替代全部导出生命周期完成。

2026-10-08：工作区文本、工作区资产和任意已注册托管附件均可通过 Core 明确复制到选定沙箱，复用 FILE_COPY 后台任务/停止/文件变更记录；不挂载来源，也不自动执行或同步。下文“工作区到沙箱复制”待补由本节更新，反向导出与完整设备运行仍待补。

### 复制到沙箱

- `startCopyAssistantWorkspaceFileToSandbox(assistantId, workspacePath, sandboxId, targetPath)`：复制当前逻辑文本的 UTF-8 内容，不增加换行；起始读取后工作区编辑不改变该任务输入。空文件也能复制，文本继续遵循既有 256 KiB 工作区限制。
- `startCopyAssistantWorkspaceAssetToSandbox(assistantId, workspacePath, sandboxId, targetPath)`：从工作区资产索引取得附件 ID，再读取真实托管附件；不信任索引中的外部路径，也不把元数据当文件内容。
- `startCopyAttachmentToSandbox(attachmentId, sandboxId, targetPath)`：用户明确选择已注册托管附件复制，包括已导入的二进制和大文本；复制不使用模型媒体输入的 MIME/6 MiB 限制，沿用原复制任务的 8 GiB 上限。

三项返回普通 TaskSnapshot。使用 `getFileCopyProgress(taskId)`、`observeTasks`、`stopTask` 和原文件变更查询；成功后以 progress.targetPath 为实际文件位置，已有目标不覆盖，沿用原任务的冲突改名。原文快照先落本任务私有输入，再走同一发布/元数据提交和清理流程；没有新任务类型、源路径持续句柄、外部目录挂载或自动重放。停止/重启仅保留已完成的副本或标记未完成状态，不隐式重试。

这是 Core 的用户显式复制入口，不新增绕过原审批的模型复制工具，不绑定运行任务到后来切换的沙箱，不自动将工作区所有文件搬过去。复制后可用原沙箱 shell/read_file 工具操作副本；修改副本不会回写工作区、来源包或附件。反向从沙箱导出到工作区、整目录复制、巨大文件实际设备表现和完整备份关系仍未验收。

主线实施：2026-10-07。接入代码和普通离线构建；未运行用户导入/恢复、模型或设备，不表示完整助手/后端验收。

### 工作区与角色资料库

每个 assistantId 有自己的持久工作区，使用同一配置的助手聊天共享；不同来源映射到不同配置，不拼到一个全局目录。文本逻辑文件保存于现有 Room source_blobs 的专用命名空间，二进制/大文件是原 AttachmentStore 托管文件加工作区索引。没有新增 Room 表/版本或新的文件任务引擎。工作区不是可执行 Linux 沙箱挂载；shell/read_file 仍访问选定沙箱，workspace_* 访问助手文本工作区。

文本文件路径只能为相对路径，反斜杠转为斜杠；禁止绝对路径、冒号、控制符、空路径段、`.` 和 `..`。单文件正文256 KiB。分页返回路径/更新时间，不加载所有正文；默认100、最大200条，afterPath/nextAfterPath/hasMore 使用稳定路径顺序，二进制索引单独分页。

写入 expectedContent 为最后读到的完整正文，事务比较 actual content，不使用哈希；null 仅允许新建，不是强制覆盖。删除同样需要已读正文。发布、文件写入和删除使用原 writeData/Room，工具沿用分类审批，停止不会撤销已经完成的编辑，不恢复旧调用。

### Core 入口

- listAssistantWorkspaceFiles / listAssistantWorkspaceAssets(assistantId, afterPath="", limit=100)。
- readAssistantWorkspaceFile / getAssistantWorkspaceAsset(assistantId, path)，后者返回托管 AttachmentRef。文件不会因查询被发给模型；用户明确选 attachment.id 后可沿用 startAssistantGeneration 的 attachmentIds。
- writeAssistantWorkspaceFile(assistantId, path, text, expectedContent)、deleteAssistantWorkspaceFile(assistantId, path, expectedContent)。
- beginWorkspaceCharacterCard(assistantId, characterId?, expectedContent?)：复制资料库卡或创建空白卡，仅保存 character_card.json 草稿。
- patchWorkspaceCharacterCard(assistantId, patch, expectedContent)：已知 Android 卡字段补丁，不替换 ID/来源/创建时间；允许正文空字符串清空，与桌面补丁的空串限制不完全一致。
- replaceWorkspaceCharacterCard(assistantId, card, expectedContent?)：完整 Android CharacterCard 对象保存为草稿，不自动修改库。
- publishWorkspaceCharacterCard(assistantId, expectedContent, targetCharacterId?, expectedUpdatedAt?)：明确新建/更新资料库；新建生成新 ID，更新保持目标 ID、创建时间、来源/未知原字段。目标更新需最新 updatedAt，草稿需完整正文前提。头像未提供保持原头像，提供时必须是已注册托管引用；不安装外部路径。附加世界书必须已存在。发布后仍保留草稿，重复新建是新的角色，不声称幂等发布。

默认本地助手配置只在原 createAssistantChat("default") 或显式保存配置时创建；工作区接口不会因缺配置自动创建来源助手。

### 助手工具

所有助手均按绑定 assistantId 使用同一工作区，不需绑定主聊天：workspace_list_files / workspace_list_assets / workspace_get_asset / workspace_read_file、workspace_create_file / workspace_write_file、workspace_begin_character_card / workspace_patch_character_card / workspace_publish_character_card。workspace_delete_file / workspace_replace_character_card 还需 allowDestructiveTools。普通读工具为 READ，增改和发布走原 WRITE/EDIT 审批。

工具前提字段名为 expected，与 Core 的 expectedContent 相同。patch 工具用 patch 对象，而不是桌面的任意顶层字段；card 对象使用 Android 字段格式，不宣称全套桌面 wire schema 兼容。generic workspace_write_file 也能编辑卡 JSON，破坏性开关并不限制所有内容层面的覆盖语义；与原普通文件工具一样，不用内容黑名单模拟权限。草稿坏 JSON 可保存为普通文本，但 patch/replace/publish 在卡边界拒绝坏对象。

资料库 chat_patch_character_card 与上述草稿流程分开：前者立即编辑已批准的参与角色，后者只有 publish 才进入库，不能混为一谈。

### 桌面导入

源导入新增 ai_workspace 遍历：小型已知 UTF-8 文本转工作区逻辑文件；character_card.json 转 Android 草稿并 remap 世界书/角色 ID，复制已有头像附件并保留原字段；不发布角色。assistant_workspace_chat.json 转独立助手历史，不重放工具。其余二进制、大文本、无效 UTF-8 和 ingest 文件通过现有附件复制保留，并建立 workspace_asset 索引，不拿二进制 metadata 冒充文本正文。

助手消息的 attachments 识别 storageScope=assistant_chat/workspace_session、storageKey 和 filename，读取归档 ai_workspace/ingest 子树并转为选中候选的 AttachmentRef；缺文件报告 missing，不再整批说 workspace deferred。未知存储引用保留原 metadata 并逐项 retained，源根目录外文件不作为活句柄。图片/文本/PDF 发送仍使用原四协议托管链路，音视频/Office 等只是保留，不能据文件已导入宣称模型可读。

文件及索引用原 ImportMerge 基线三方合并并存；普通文本按整份正文合并，卡草稿将 JSON 内容展开为字段合并，未冲突字段可随源更新、本地冲突字段保留；临时坏 JSON 草稿按原文字保留。删除逻辑文本后保留导入基线，再导入不重建。来源删除不自动删本地文件。原二进制附件复用策略仍按稳定 ID/现存文件复用，没有新增文件哈希或完整二进制三方合并；同源文件内容修订、全旧版本存储布局/重复拷贝优化仍需补。

这些操作仅在明确再次导入时执行；旧 source_blobs/assistant_messages 不在启动时迁移，旧附件/工具记录不会自动执行。RikkaHub 仍沿用原角色/助手/附件转换与来源隔离，不编造桌面 workspace 存储布局。

### 备份与仍待补

工作区记录/索引位于已有 source_blobs：includeDatabase 或 includeConfig 选择时进入数据库快照；二进制真实文件还需要附件备份选项。它们不再依赖未打包的临时 workspace 目录，但完整 workspace 绑定、包外覆盖保护、缺附件及所有 native 关系仍未逐项验收。native 或来源恢复/导入都不自动发布草稿或续接助手任务。

仍待补：任意来源布局/二进制修订、动态文件关系完整恢复、工作区到沙箱明确复制/导出和完整附件格式能力、全来源 MVU/KG 行为、真实设备与上游证据。费用细项和内部防御清理继续后置，无冒烟、额外哈希或新增单测。

<a id="device-tools"></a>

## 日历与屏幕时间工具

2026-10-08：来源 `localTools` 的 `calendar` / `screen_time` 已接普通 RikkaHub 角色、群聊实际生成成员及独立助手。实际调用 Android Calendar Provider / UsageStatsManager，不以静态保留字段代替执行；沿原工具参数验证、审批、结果、模型续轮和任务停止，不新建表、依赖或任务引擎。

### 接口与权限

`getDeviceToolPermissions()` 返回 `DeviceToolPermissions`：`calendarRead`、`calendarWrite`、`usageAccess`、`userUnlocked` 和可供前端明确发起的应用权限设置/Usage access 设置 action、应用 package URI。只检查当前授权，不请求权限、授予权限或打开页面。

Manifest 声明 READ_CALENDAR / WRITE_CALENDAR 与 PACKAGE_USAGE_STATS，并声明 HOME intent 查询可见性，以定位桌面包。前两项仍需用户 runtime 授权，使用情况仍需系统特殊访问授权，声明不等于已获权限。前端授权/设置入口本批不做；工具缺权限返回 `NO_PERMISSION`，需要用户授权后以新调用继续，不缓存或自动重放旧调用。

### 日历

- `calendar_query(begin?, end?, range?, query?, limit?)`：READ。默认当天，week 为当地本周周一开始的七天，month 为当地本月；自定义 begin 覆盖默认起点。默认结束分别为明日/起点七天后/起点一月后，与固定上游一致。limit 默认20、范围1..100。查询实际 Instances，重复事件返回实例；开始包含、结束不包含，返回重叠事件。标题 query 为忽略大小写的字面子串，不将 `%` / `_` 当作通配符。返回 range_start/range_end/count/events（id/title/description/location/start/end/all_day/calendar）。
- `calendar_create(title, start, end?, description?, location?, all_day?)`：WRITE 且按上游始终逐项确认，auto 策略也不能绕过该确认。只新建实际事件，不修改/删除既有日历。优先可写、同步启用的主日历，再可见日历，ID 作稳定排序；没有可写日历返回 NO_CALENDAR，不创建账户。普通事件默认一小时，全天默认下一天，结束日期不包含，按 UTC 午夜保存全天起止。只有 provider 返回事件 URI 才返回 success/event_id。

时间接受 epoch 毫秒、ISO 日期、本地日期时间、带偏移日期时间；无偏移按实际设备时区，全天返回日期而非偏移时间。查询在 IO 中执行，取消信号接当前调用；创建前检查取消，但 Android provider insert 与本任务不是跨系统事务。停止/进程退出时已插入事件保留，不自动删除或重发，不能声称取消必定没有副作用。

### 屏幕时间

`get_screen_time(begin?, end?, range?, top?)`：READ。today 从当天午夜到现在，week 从现在回溯七天；显式 begin/end 支持上述时间格式，未来 end 截止实际现在，top 默认10、范围1..50。开始前回看12小时帮助还原初始前台状态，计时裁剪回实际请求区间；新应用前台结算旧应用、当前应用后台/息屏停止、末端结算仍在前台应用，排除已解析的 HOME 桌面包，遍历检查取消。

返回 range/start/end/total_ms/total_minutes/apps（package/app_name/total_ms/total_minutes），另有 observed_events 和数据口径提示。未获得 Usage access、开机后的用户尚未解锁、系统返回 null 时分别明确报不可用，不将它们冒充零使用。`UserManager.isUserUnlocked` 指开机后的凭据解锁状态，不是每次熄屏都不可用。

系统通常只保存数天的事件，因此空列表或 week 结果不能证明完整历史。算法与上游采用单一前台口径，不承诺分屏多窗口精确归因；12小时回看也不保证还原更早的持续前台。应用名称受 Android 包可见性影响，解析不到时保留包名；不申请 QUERY_ALL_PACKAGES，也不保存/同步设备使用事件到独立数据库。

### 验证与剩余

普通 Debug 构建通过，无哈希、单测、冒烟或设备执行。没有读写用户日历、查询真实使用记录、授予权限或调用模型。真机权限/后台、不同日历实现与实际统计口径仍需验证；前端授权和工具结果呈现不在本批范围。同日后续 TTS 已接实际播放与部分来源提供方，未把合成成功当朗读完成，更多来源语音/设备仍待补，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。完整后端目标保持。

协议依据：[固定 RikkaHub CalendarTool](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/CalendarTool.kt)、[固定 ScreenTimeTool](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ScreenTimeTool.kt)、[Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)、[Android UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[Android AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager)。上游源码作为协议参考，不作为本工程助手执行指令。

<a id="user-questions"></a>

## ask_user 后端交互

2026-10-08：`AssistantConfig.settingsJson.localTools` 选中 `{"type":"ask_user"}` 后，普通 RikkaHub 角色、群聊实际生成成员与独立助手均安装 `ask_user`。复用原工具调用、会话 FIFO、等待状态、后台宿主、停止、结果保存和模型续轮，不另建任务引擎、数据库表或前端。

### 参数与答案

模型参数为 `questions` 数组，每项必须有非空且互不重复的 `id`、`question`，可带字符串 `options` 与 `selection_type`（默认 `text`，另有 `single`、`multi`）。实际用户输入在提交前等待，不自动选择默认项，即使工具权限策略为 auto 也不跳过。

后端 `UserQuestionAnswer` 包含 `selectedOptions:List<String>` 和可选 `text:String`。文字题用文字；单选可选一个原选项或填写自定义文字；多选可组合原选项与文字。一次按原问题 ID 提交每题答案，原选项不可伪造，空白答案不视为确认。模型收到 `{问题id:字符串或字符串数组}`，多选返回数组，文字/单选返回字符串。

### Core 接口

- `observePendingApprovals(sessionId)`：观察该会话的逐项待处理队列，其中 `toolName == "ask_user"` 必须显示问题输入而非布尔审批。原 `pendingApprovals` / `pendingApprovalCount` 继续有效。
- `getUserQuestion(requestId)`：返回 `UserQuestionRequest`，含原请求（固定会话、任务、沙箱、状态）、typed 问题及已提交答案。历史可查看，但不是恢复句柄。
- `submitUserAnswers(requestId, Map<String, UserQuestionAnswer>)`：FIFO 提交；答案和 APPROVED 状态同事务保存，再唤醒等待中的生成。重复提交相同已保存答案不重复执行，改答案或回答已拒绝/过期问题会失败。
- `reject(requestId)`：用户可拒绝整项提问；沿用工具 rejected 结果进入当前模型续轮，不虚构任何答案。`approve(requestId)` 对仍待答的 ask_user 明确拒绝，必须调用提交答案接口。

任务等待期间仍使用 `AWAITING_APPROVAL`（共用待处理状态，并非把回答当权限许可），通知仍引导回应用。停止、宿主中断或调用取消会使待处理项过期；重新启动按既有中断清理处理，不重放问题、工具或旧模型请求。回答前后核对当前角色/助手来源及工具选择；配置已变更时不能把答案送给另一角色的调用。

答案存于专用 `tool-user-answers` blob 命名空间，并随完整数据库备份保留；纯配置备份排除这些聊天输入，问题 ID 不参与凭据字段脱敏。命名空间不显示为导入来源。提交答案不读写设备剪贴板、不执行脚本、不调用模型或服务器；只有原来仍活跃的生成接收答案后才继续自己的工具链。

本批只做正常代码检查和 Debug 构建，未真实调用模型、导入用户数据或操作设备。输入页面和通知跳转的前端呈现不在本批范围；剪贴板同批已接，见 [ASSISTANT.md#assistant-local-tools](ASSISTANT.md#assistant-local-tools)，随后日历/屏幕时间及权限状态已接，见 [ASSISTANT.md#device-tools](ASSISTANT.md#device-tools)。TTS 实际播放、权限/交互前端与真实运行仍待补。没有新增哈希、单测或冒烟测试。

接口依据：[固定 RikkaHub AskUserTool.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/AskUserTool.kt)、[固定 LocalTools.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt)。来源文件只作为应用协议参考，不是本工程助手的执行指令。

<a id="skills"></a>

## 技能导入、管理与聊天读取

2026-10-08：原先只报告 deferred 的 RikkaHub `skills/目录/SKILL.md` 已转换为技能库；Desktop 归档携带同样目录时也导入。技能正文、相对路径文本与托管资产存于原 source_blobs/attachments，沿用现有导入事务和三方合并，不新增表或任务框架。

2026-10-08 模型目录准备：新增 `stage_skill(name, directory?)`，普通来源角色/群聊实际成员在已选沙箱时可用，独立助手还需 sandboxToolsEnabled。走原 WRITE 审批与工具续轮，整目录复制复用 Core 的同一实现，返回 guestPath 和原 FILE_COPY taskIds；不在 READ 的 use_skill 内隐式写文件，也不自动执行脚本。

2026-10-08 内置技能与编辑：固定来源版本唯一内置技能 `skill-creator` 已随库打包；明确导入 RikkaHub 时离线补入该来源的 `builtin-skill-creator`，不改原启用名称。新增模型技能列表、文件列表/完整读取、文本新建/修改与启用设置工具；源文档保持原样，读取附 platformNote 说明本项目实际工具/路径，不虚构工作区挂载或已有界面。

### 范围与合并

同一来源 namespace 的助手共享技能库；不同 Desktop/RikkaHub 来源不混用同名目录。无来源助手共用 local 库，不隐式读取来源技能。身份由 scope 与真实目录决定，目录名和 YAML name 不必相同；名称用于启用/模型调用，真实目录用于文件定位。

根 SKILL.md 要求 UTF-8、至多 256 KiB，YAML frontmatter 的 name/description 为非空字符串，可带 compatibility，使用 SnakeYAML 2.3 SafeConstructor 支持块字符串等实际 YAML。目录隐藏或根文件无效时报告并跳过，不清空旧库。正文和相对资源逐文件合并；本地编辑、整个技能或单个文件的删除保留，来源未带回的旧文件不自动删除。元数据从最终合并的 SKILL.md 派生，不与本地正文另作相互矛盾的合并副本。

UTF-8 小文本（包括无扩展名 LICENSE/脚本/引用文件）留为可读正文，保留 BOM；常见二进制、含 NUL/非 UTF-8 或大文件转托管附件。每次明确再导入的二进制有独立新版本引用，未本地修改的文件可跟随来源更新，不用哈希或只凭大小猜内容不变。这会产生旧/未采用附件字节，孤儿回收仍后置；不会因删除技能破坏共享附件。

### Core API

- `installAssistantSkill(assistantId, archive: File/Uri, options=SkillInstallOptions())`：独立 ZIP 安装，Uri 使用 content 提供方，不要求整包 Desktop/RikkaHub 导入。
- `installAssistantSkillFromGitHub(assistantId, GitHubSkillSource(owner, repository, ref=null), options=...)`：明确请求公开仓库 ZIP，ref 为空使用默认分支，也可指定分支/标签/提交。仅 GitHub 官方归档地址；不使用账号凭据、git clone 或运行仓库命令。
- `exportAssistantSkill(assistantId, directory, destination: Uri)`：将本来源的一项技能导出为 ZIP 到调用方提供的 SAF Uri。
- `listAssistantSkills(assistantId)`：返回技能元数据与本助手 enabled 状态。
- `listAssistantSkillFiles(assistantId, directory)` / `readAssistantSkillFile(assistantId, directory, path="SKILL.md")`：文件列表和完整文本/附件引用，不执行文件。
- `writeAssistantSkillFile(..., path, text, expectedFile=null)`：新建根 SKILL.md 即建立技能；覆盖使用最近读取的完整 SkillFile，不传仅新建。资源文件要求根技能先存在；修改根文件同步元数据。
- `linkAssistantSkillAsset(..., path, attachmentId, expectedFile=null)`：关联已注册附件，SKILL.md 不能作为二进制覆盖。
- `deleteAssistantSkillFile(..., expectedFile)` / `deleteAssistantSkill(..., expectedSkill)`：显式删除引用/文本；删除根等同删除整个技能。保留来源基线及共享附件字节。
- `setAssistantEnabledSkills(assistantId, names, expectedUpdatedAt)`：显式启用该库中已有技能，空集合关闭。文件编辑/删除/启用同 Room 事务；名称修改或最后一个同名技能删除时，同 scope 助手的旧选择同步替换/清理，不改其他来源。
- `startCopyAssistantSkillFileToSandbox(assistantId, directory, path, sandboxId, targetPath)`：用户明确选择后，把文本快照或托管附件复制到指定沙箱；使用原 FILE_COPY 宿主/进度/停止接口，不覆盖已有目标、不自动执行、不回写技能库。脚本与资源可据此接现有 shell 工具。
- `copyAssistantSkillToSandbox(assistantId, directory, sandboxId, targetDirectory=null)`：整技能快照按原相对目录复制，等待完成并返回 SkillSandboxCopyResult；不必逐个调用单文件接口。默认生成新的 `home/skills/目录-UUID`，也可明确指定一个尚不存在的相对目录。

#### 独立导出

`exportAssistantSkill(assistantId, directory, destination: Uri)` 导出一个技能包，不是数据库备份。ZIP 保留单一技能目录，以及 SKILL.md、脚本、引用文本和托管资产的原相对路径；托管资产按其当前字节读取。短 Room 事务先读取定义、完整文本和资产引用，事务结束后仅持共享 data gate 生成完整缓存 ZIP；压缩和文件 I/O 不在 Room 事务内。随后释放 gate，再写入调用方提供且已授权的 Uri。完成或失败时只清理本次导出缓存。

文件数（含可能附加的许可证）最多 512，展开数据最多 256 MiB，压缩 ZIP 最多 128 MiB，与独立安装限制一致。导出固定内置 `skill-creator` 时，若包内没有 `LICENSE.txt`，附加打包的原许可证；已有该文件时保留原文件，不覆盖。ZIP 不保存 `builtin` 元数据，重新安装按普通用户技能包处理，并沿既有目录冲突自动并存规则安装；不改变原 builtin 或来源基线。

导出不自动授予 URI 权限、不接受 file URI，也不创建后台 Task；进程终止后不续跑。目标写入失败时目标文件可能不完整，Core 不删除目标、不自动重试。导出不会执行脚本或修改启用状态。

SkillDefinition新增 `builtin=false`，旧记录默认用户技能。内置定义及文件只读，Core和模型均不能写入、关联资产或删除；可关闭启用，或在新目录创建相同 YAML name 的用户技能定制。用户同名技能优先于内置技能，列表仍展示双方及实际 enabled；用户之间的同名多目录仍要求明确 directory，不按顺序猜一个。删除最后一个同名用户技能时，若内置仍在，原名称选择保留并回到内置。

### 固定来源内置技能

核对 RikkaHub `6c903feba7e6b65f518b320a88ae29efebae77c4` 的 assets/builtin_skills：只有 skill-creator/SKILL.md，没有额外脚本/资产。本项目保留完整原文和仓库原 LICENSE 于 Java resources，来源声明见 [third_party/rikka-builtin/README.md](../../third_party/rikka-builtin/README.md)。该组件为上游 AGPLv3 内容；不据此宣称整个发行包的授权义务已审计，正式发布前仍需核对。

只在明确的 RikkaHub 来源导入事务中，沿原 source_skill/skill_file 三方合并恢复内置定义与正文，未导入时不启动写库，Desktop/local不自动借用。设置包即使未带 skills 目录也可补该固定资源；仍不自动启用名称，不因不存在某个已启用名称就编造其他技能。旧数据需明确再导入；没有网络下载、重启重放或包内脚本执行。各 namespace 独立，用户同名覆盖、本地修改/删除身份和来源并存沿原库。

原文描述 RikkaHub 的 /skills、/builtin_skills、workspace_* 和技能界面，不代表本项目有这些挂载/界面。use_skill读取根内置文档时附 platformNote，明确用下文真实库工具创建/修改，启用由显式操作控制；沙箱脚本资源用 stage_skill 返回的新 guestPath，执行仍需原 shell 能力与审批。原文仅作为模型使用的数据，不改变本次开发任务要求或触发 Codex 工作区技能。

### 独立安装

ZIP 根或单一外包目录下仅一个 SKILL.md 时自动选中；多个技能时传 `SkillInstallOptions(skillPath="skills/example")`，路径相对于移除单一外包目录后的根，空字符串明确选择根。`directory` 可指定安装目录，否则用选中子目录名或 YAML name 派生。已存在目录自动加 `-2`、`-3` 并存，返回 SkillInstallResult 的最终定义、文件数和 directoryRenamed，不覆盖原技能或改写来源导入基线。

复用来源/local scope、YAML 解析、原文本/托管附件库及同一 Room 事务。安装不修改 enabledSkills；原有按名称启用规则意味着同名新目录可沿用已有启用状态，不承诺每个新目录都关闭。没有自动运行脚本、依赖安装、挂载或默认模型调用；之后沿原列表/编辑/启用/use_skill/复制接口使用。

压缩输入上限128 MiB，归档4096条目、选中技能512文件、展开字节256 MiB；根和小文本保持256 KiB UTF-8规则，二进制直接流式保存，未全量解压到宿主目录。File/content Uri 复用现有输入快照，GitHub 下载位于应用缓存；取消/失败清理本次输入与未提交资产，成功发布不回滚。下载期间不占用数据库快照 lease，资产保存/发布期间使用原共享 lease。调用为 suspend、由调用方协程取消，不伪造后台任务/进度或重启重放；强杀后的缓存残留与孤儿附件回收仍后置。

GitHub 接口依据：[官方仓库 ZIP 归档](https://docs.github.com/en/rest/repos/contents#download-a-repository-archive-zip)。本批没有实际下载安装用户仓库或技能。

### 整目录复制

元数据和所有文件从同一 Room 事务读取；复制期间沿原沙箱 reservation/数据 lease，后续编辑不改变本次文本快照。托管资产走原文件描述符快照，不用哈希或重新解析技能正文。512文件/256 MiB上限与独立安装一致，保留 SKILL.md、脚本、引用及资产的相对路径；默认目录在 guest `/home/skills/` 下。

每个文件沿现有 FILE_COPY 宿主、进度、事件、停止和启动恢复，FileCopyProgress新增 exactTarget 默认false。整技能的子任务设true，目标冲突明确失败，不用普通单文件复制的随机改名破坏资源引用；原单文件入口行为不变。返回每个已开始文件的 path/原进度（含taskId）、completed、失败路径/错误。首个失败或被停止后不再开始下一文件；调用协程取消时停止当前子任务并抛取消，已发布文件和原任务记录保留，不删除可能已被用户编辑的输出，也不重启重放整技能。

这是显式、多文件、逐个发布的复制，不是整个目录的原子提交或只读挂载；失败/强杀可能留下部分目录，不能把根 SKILL.md 存在当成整体完成。批次本身没有新后台任务ID，取消后各子任务仍可沿原任务/文件事件查询。脚本不自动执行、不自动chmod/装解释器，不回写库、不改变启用选择；运行需明确走已有沙箱工具并具有真实运行环境。

### 聊天调用

RikkaHub 普通角色、群聊实际生成成员及独立助手读取 enabledSkills，安装 `use_skill(name, path?, directory?)`；与记忆/历史/MCP 开关独立，归 READ，复用原参数验证/工具审批/结果保存/模型续轮。已关闭或不属于本来源的技能不可调用，调用前以当前配置选中状态为准。

上下文只注入已启用名称、真实目录与最多 1024 字符说明的 available_skills 元数据，转义 XML 文本，不把全部正文常驻系统提示。默认调用返回去除 frontmatter 的正文；明确 path 返回相应完整文本，二进制返回附件元数据及复制提示。不自动下载资源链接、挂载技能目录或执行引用的脚本。目录/名字不一致正常定位；同名多个目录可通过 directory 精确选择，不随机用第一个。

需要在沙箱内使用脚本或资源时，模型可调用 `stage_skill`，不用用户逐文件复制。只接受本助手当前启用的技能名称与可选真实目录，不能指定其他助手、来源、沙箱或宿主路径；目标始终为本次聊天所选沙箱内新的 `/home/skills/目录-UUID`。工具调用前检查聊天绑定和沙箱未切换，读取复制快照时检查来源/启用仍有效；复制期间库编辑不改变已取得快照。未选沙箱时不宣告该工具；独立助手关闭沙箱工具时也不宣告。

返回 `completed`、实际 guestPath、已发布文件数、所有已开始子任务 ID，以及可能的 failedPath/error；失败返回 ok=false，不把部分目录当成成功。运行态和持久工具结果保留目录与任务关联，不返回宿主路径或附件实体。生成停止/调用取消沿现有复制收尾停止当前子任务，已发布文件保留；重启不重放调用。每次调用复制新快照，不缓存可被用户修改的旧目录。后续脚本执行仍走已配置的 shell 等工具及其原审批，不额外开放 shell、安装解释器、chmod 或静默执行。这里只是可写复制，不是只读挂载、原子目录提交或执行环境已验收。

技能内容仅是本产品用户启用的模型上下文/工具数据，既不是 Codex 工作区技能，也不是本次编码助手的更高优先级指令；不因归档包含 SKILL.md 就让编码助手或 Android 自动运行其中命令。

#### 模型库编辑

当原技能工具可用（助手至少有一个实际启用技能）时，沿原工具循环提供 `skill_list()`、`skill_files(directory)`、`skill_read_file(directory,path)`、`skill_write_file(directory,path,content,expectedContent?,expectedUpdatedAt?)`、`skill_link_asset(directory,path,attachmentId,expectedUpdatedAt?,expectedAttachmentId?,expectedContent?)`、`skill_delete_file(directory,path,expectedUpdatedAt,expectedContent?,expectedAttachmentId?)`、`skill_delete(directory,expectedUpdatedAt)`、`skill_set_enabled(names,expectedUpdatedAt)`。列表返回 configUpdatedAt 与完整选择；读取根文档保留 frontmatter，读取文件返回 content/updatedAt。仅本助手来源库，不允许模型指定助手或其他来源。关闭全部技能后，后续管理调用拒绝；下一次生成按新启用状态重新声明工具，不偷偷刷新已冻结的请求工具定义。

新文本文件及新资产关联不传 expected 字段，已有路径失败而非覆盖；覆盖资产必须同时传 `expectedUpdatedAt` 和且仅一个旧值：旧资产的 `expectedAttachmentId` 或完整旧文本 `expectedContent`。`SKILL.md` 不能关联为 binary。删除文件必须传 `expectedUpdatedAt` 和与旧文件类型相符的完整 `expectedContent` 或 `expectedAttachmentId`；删除 `SKILL.md` 沿原规则删除整项技能。删除技能使用 `skill_list` 返回的 `SkillDefinition.updatedAt`。这些比较随后由原 Core 方法在事务内复核。新建归 WRITE，覆盖和两个删除工具归 EDIT，读取归 READ；不新增审批类别或队列。builtin 只读，工具说明限定仅在用户要求时关联或删除；解除/删除文件关联不会删除共享附件实体或字节。先写 SKILL.md 建技能；根正文更新同步元数据和原名称选择。只有明确设置启用集合才改变配置，不在创建工具末尾自动启用新名称。

### 尚未完成与验证边界

已接归档内技能、独立 GitHub/ZIP 安装与导出、Core/模型管理（含文本、资产关联、文件/技能删除及启用）、按需读取、单文件及整目录显式复制、模型按需 stage_skill，以及固定来源唯一内置技能离线重建。无需工具调用的自动 staging、沙箱只读挂载及原生媒体展示仍待补；未知/未来版本的其他 builtin 未重建。普通非 RikkaHub 角色目前无隐式技能配置关联，独立助手可显式启用其库；私有仓库认证/技能自动升级仍待补。

库记录沿原 source_blobs 的 database/config 备份选择，二进制沿 attachments 的现有选择；完整恢复必须带所需记录与字节。本批普通离线 app:assembleDebug 成功54秒，编译/打包证据见 [VERIFICATION.md](VERIFICATION.md)。未实际导入用户归档、创建/删除/导出用户技能、请求真实文档提供方、复制/执行脚本、调用模型或运行真机；完整 native/部分包恢复及导出回装未验收。不新增哈希/单测/冒烟测试。完整后端目标继续。

依据：[固定 SkillManager](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt)、[按需工具](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt)、[frontmatter](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/files/SkillFrontmatterParser.kt)、[SnakeYAML 发布](https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.3/snakeyaml-2.3.pom)。

<a id="mcp"></a>

## MCP 工具后端

### 通知收集 Flow（2026-10-09）

新增 `observeAssistantMcpNotifications(assistantId, serverId, resourceUris=emptyList())`，是 collector-owned cold Flow：创建 Flow 不联网，开始 collect 才初始化并选择该助手当前来源下已选中、启用的服务器，订阅明确给出的资源 URI，并接收 `notifications/resources/updated`、resources/tools/prompts `list_changed` 四类提示。能力 DTO 在原五个位置参数尾部追加 `resourceSubscriptions`、`resourceListChanged`、`toolListChanged`、`promptListChanged`，默认均为 false。通知只含方法和可选 URI；URI 仅作标识，不会自动读取资源、执行工具或写入聊天。该 API 不注册 Task、数据库表或全局客户端表，也不在后台创建/保存订阅会话。普通离线编译/打包通过，耗时 67 秒（230 tasks：23 executed、207 up-to-date，exit 0）；未连接真实 MCP 服务器或设备验证，完整后端目标未完成。

每次交付前重核来源、服务器配置和凭据；HTTP 等待 SSE 时不持有 dataGate lease，stdio 继续持有原 sandbox lease。collection 空闲时不追踪配置变化或自动 refresh OAuth；host 取消后重新 collect 会重新选择配置。取消时退订等待窗口为 1.5 秒，之后关闭连接；该窗口不保证总体关闭时长，stdio 同时结束原 PRoot 进程。Streamable HTTP GET SSE 返回 405 明确报 Unsupported；有 event id 时遵守 `retry` 并用 `Last-Event-ID` 续收同一流，不重发订阅、工具或初始化。流结束/错误且无 cursor 会按更新可能缺失而终止。通知 GET 保持长连接，不设闲置读超时，使安静服务器可继续保持连接；单 RPC 等待仍为 120 秒。每个 collector 最多 64 个非空 URI、每个不超过 64 KiB，事件队列容量 256；慢消费者导致溢出时失败，不静默丢弃。通知协议依据：[MCP resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources) 与 [MCP transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。

通知收尾的最后修正已再次离线编译/打包通过29秒（230 tasks：10 executed、220 up-to-date，exit 0）：失效会话不为退订新初始化。最新构建证据见 [VERIFICATION.md](VERIFICATION.md)，不增加真实运行证明。

### HTTP OAuth主链（2026-10-09）

已接 discoverAssistantMcpOAuth、startAssistantMcpOAuth、completeAssistantMcpOAuth、cancelAssistantMcpOAuth、getAssistantMcpOAuthStatus、refreshAssistantMcpOAuth、logoutAssistantMcpOAuth。McpServerConfiguration.oauth 为公开 McpOAuthOptions：enabled、clientId、redirectUri、scopes、authorizationServer、clientMetadataUrl、allowDynamicRegistration。oauth.enabled 不再要求手工填 Authorization，而由登录记录管理 Bearer；静态认证头/stdio加密环境变量仍沿原接口。导入不登录/注册/刷新、不接收来源旧token，Desktop/RikkaHub自动合并并存及本地保护不变。

明确发现/开始会探测所配HTTP地址响应头，不读可能持续的SSE body；使用challenge的resource_metadata或资源路径/root well-known，绑定同源资源路径。支持OAuth/OIDC发现，issuer匹配及PKCE S256；多AS须明确选择authorizationServer。作用域按challenge、配置、资源scopes_supported，start的显式scopes与challenge合并；授权/兑换/刷新携resource，不将token放query。客户端依次用预登记clientId、调用方真正托管的HTTPS clientMetadataUrl、允许时动态注册；只支持public client/auth method none，动态注册记录加密保存复用，不虚构域名。

start返回McpBrowserLogin，由宿主打开authorizeUrl，complete提交完整callbackUrl；不代开浏览器/监听端口。默认回调为 http://127.0.0.1:1456/oauth/mcp/callback，无监听时可从浏览器地址复制完整URL；可改为真正登记的HTTPS/loopback回调，不支持自定义scheme。检查redirect/state/可选issuer/重复参数；待登录10分钟、仅进程内，cancel清待登录。兑换前消费loginId，失败或协程取消须重新start，不重发code。

token/refresh token进原加密凭据库（oauth:mcp:），同来源namespace+serverId共用、本地按assistantId隔离；URL/传输/OAuth选项变化不借旧记录。DTO/配置/普通备份无token/code/verifier，普通密钥接口不可操作oauth:记录；原生明确包含凭据的密码加密导出会带出记录，但完整关系审计尚缺。status离线，authorized表示存在当前绑定记录，不保证远端grant未撤销；expiry未知则null、不猜时限，needsRefresh/canRefresh分别报告。

新连接在到期前30秒按需刷新；不可刷新的有效token用到实际到期，之后重登，无expiry可显式refresh。保存旋转refresh token，未返回则沿用原值；invalid_grant移除原匹配记录。logout只清本地登录/动态客户端/待登录，关闭OAuth后仍可退出，不代远端撤销。认证联网不持data gate，提交复核绑定/原凭据；现有生成只离线比较冻结token，不在工具轮刷新/重放。HTTP401/403仅返httpStatus、已知oauthError及oauthScope供显式追加授权，不返原认证头/错误body。

普通离线Debug编译，未连接用户服务器或登录账号；S256是PKCE协议密码操作，不是新增文件哈希校验。无新表/依赖/引擎/前端/冒烟/单测。尚缺浏览器回调UI/监听、confidential client/private_key_jwt、设备码/PAR等扩展、远端撤销和真实服务验证，不宣称全OAuth兼容。collector-owned通知订阅已部分接入，边界见本文顶部；协议依据：[MCP Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)。以下OAuth全部尚缺为历史状态，以本节为准。

### stdio沙箱传输（2026-10-08）

原McpClient现支持stdio，复用初始化/版本协商、JSON-RPC ID配对、工具/资源/提示词/补全、原工具审批与媒体映射。配置明确选定已有初始化的sandboxId，command/args在该PRoot Linux根、/home工作目录执行；参数按argv传递，不隐式交shell、不绑定桌面或Android宿主目录，不自动下载Node/Python/服务器依赖。执行程序可修改沙箱、使用网络，工具审批不是对服务器启动行为的逐条审计。PRoot仍不是内核隔离，真机/子进程/软件包未运行验收。

McpServerConfiguration增command、args、environment、credentialEnv、sandboxId，stdio的url可为空；同来源settings中的env/environment及助手覆盖可解析，只有command未列type时按stdio。配置读取/保存、导入与启动不运行服务器，只有明确的新生成、发现、资源/提示词操作才启动。当前桌面源码未发现MCP配置实现，不宣称已完成独立桌面MCP格式转换；来源自动合并并存不改。移植的Windows命令/路径需明确改成Linux可用命令并选择本机沙箱，不能自动执行桌面程序。

标准输入/输出使用UTF-8换行分隔JSON，每条仍限8MB；stdout不是日志通道，非法JSON/无换行/超限或EOF结束该连接，不重新启动/重放调用。stderr持续读取但不持久化或混入模型/错误文本。请求仍120秒，取消通知沿原链；阻塞写入取消时关闭进程，连接关闭回收原进程组/已跟踪子进程、管道及私有控制目录，不持久化PID/进程续跑句柄。HTTP两种传输不改为本地启动。

environment仅放非敏感值；credentialEnv声明需从加密存储取得的名称，新增setAssistantMcpEnvironmentCredential(assistantId, serverId, name, value?)保存/移除。作用域与认证头相同，env名区分大小写；stdio不把HTTP头当环境变量，不继承宿主环境。导入env/environment的敏感键只保留缺凭据占位，不导入/刷新旧token。不要把密钥写进command/args，未提供argv秘密自动迁移或变量展开；需要shell语义应明确配置shell。调用前重核原配置、选中状态及本轮环境凭据快照。

连接持原沙箱activity/data lease防删除/初始化/原生恢复竞争，并复用文件事件观察；事件仍unknown/incomplete，stdio观察taskId=null，不伪造不存在的通用任务ID。没有新增表、依赖、任务引擎、哈希、冒烟、单测或前端；仅普通编译，未实际启动用户程序/服务器。协议依据：[stdio传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。以下旧段中stdio未接状态由本节更新，其余限制保留。

### 资源与提示词（2026-10-08）

已接 `getAssistantMcpCapabilities`、`listAssistantMcpResources`、`listAssistantMcpResourceTemplates`、`readAssistantMcpResource`、`listAssistantMcpPrompts`、`getAssistantMcpPrompt`、`completeAssistantMcpArgument`。每次明确指定 assistantId/serverId，只连接该助手当前选中且启用的服务器，凭据仍从来源隔离的加密存储读取。读取配置后释放数据 gate，不在远程等待期间占用恢复锁；结果属于本次配置快照，不自动写回配置。2026-10-08补齐明确应用入口，见下节。

列表在同一连接内读完 nextCursor 分页再关闭，不将可能依赖会话的 cursor 交给下一条新连接。每类发现最多512项、最多512页，保留工具原分页检查及完整 schema；资源、模板和提示词的 icons/annotations 等描述在 metadataJson 内保留。没有 tools 时返回空工具列表，不再阻断只有资源/提示词的服务器；不支持的资源/提示词/补全能力明确返回 Unsupported。

资源返回 `McpResourceResult.contents`（uri/mimeType/text/mediaIndex/metadataJson）与请求内 `media`。blob 用原提取器解码，metadataJson 不重复保存 base64。URI 仅作为 resources/read 参数交给服务器，file:// 不打开 Android 文件，https:// 不直接下载；工具的 resource_link 也不自动读取。

提示词返回 `McpPromptResult` 草稿：messages 保留 user/assistant 角色与 contentJson；图片/音频/embedded blob 的 attachmentIndex 在整个草稿中统一索引 media。description、文本/资源及未知内容保留；媒体 audience 沿原规则保存 modelVisible。取得草稿不修改聊天、启动生成、调用工具或提升为 system。补全用 `McpCompletionReference(PROMPT, name)` 或 `(RESOURCE, uriTemplate)`，argument/value 与可选 context 表示正在填写及已填参数，建议值不自动代填。

明确选择某个媒体后，可用新增的 `importChatAttachment(chatId, bytes, filename, mimeType)` 复制为聊天输入，再把 id 交给原生成入口 attachmentIds。文本可作为明确选定的输入；不伪造没有 toolCallId 的工具消息。复制仍受原单文件6MiB/上下文预算，不改来源合并并存、Room schema 或任务引擎。

本适配仍固定协商2025-11-25及原兼容版本，不宣称支持后续无初始化的新协议。stdio、提示词整组应用、URI模板展开、HTTP public-client OAuth主链及collector-owned通知订阅已接；OAuth扩展/浏览器UI、完整native及实际服务器/设备运行仍未完成。collector本身保持长连接；共享池、跨进程保存和后台宿主不属于本轮提供的生命周期扩展。没有新增文件哈希、冒烟或单测。

协议依据：[资源](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)、[用户选择的提示词](https://modelcontextprotocol.io/specification/2025-11-25/server/prompts)、[参数补全](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion)。

### 提示词整组应用（2026-10-08）

`applyAssistantMcpPrompt(chatId, draft, expectedUpdatedAt, characterId=null)` 明确把 getAssistantMcpPrompt 的选定草稿追加到现有聊天，返回 McpPromptApplication 的 nodeIds、candidateIds、attachmentIds 和 chatUpdatedAt。不发请求、不重新取草稿、不自动生成/执行工具，不清空旧历史、不改变原候选或冒充模型计费用量。只有 user/assistant 角色，按原顺序各追加一个普通节点，不升级为 system，不造没有 toolCallId 的 tool 节点。

McpPromptResult新增取得时实际配置快照的 sourceNamespace；来源与目标聊天/配置一致才可应用。独立助手需相同 assistantId；普通RikkaHub角色需实际映射同一助手，群聊可指定实际成员，不指定则按源助手映射推导。群聊assistant草稿记该成员characterId，独立助手不伪造角色。没有普通本地/桌面角色的隐式助手映射，不跨来源借用。应用冻结的已选草稿不再次连接服务器，也不要求服务器此时在线；未校验上游是否已经更新草稿。接口是明确用户操作，不新增模型自动应用工具。

text与embedded resource text按原正文保存；image/audio及embedded blob用全草稿attachmentIndex对应的实际字节保存为托管附件，保留modelVisible，将图片放images、其余放attachments。候选extraJson记录mcpPrompt来源、消息索引、原content块及实际attachmentId，保留资源URI/MIME/annotations等数据；URI不打开Android文件或下载网页。无模型generationMetadata或计费伪造。未知内容类型明确Unsupported，草稿仍可检查，不丢掉未知块后报告整体应用成功。

应用上限256消息/8MB contentJson、20个媒体、媒体合计12MiB/单个6MiB，与既有内联上下文范围对齐；不限制其他Files上传功能。所有消息先转换，媒体以64KiB块保存到本次新文件；取得媒体和生成candidate前后的取消可清理未提交文件。文件注册及全部节点同一Room事务，事务前检查expectedUpdatedAt和无活跃单聊/群聊生成，在最终事务中重核；失败不发布部分消息/附件记录。提交开始后完成优先，已提交记录/字节不在取消时回滚，重复旧expectedUpdatedAt失败；这不是跨调用去重框架，也不阻止用户重新读取版本后再次明确应用。

此操作不改设置、启用选择、MCP凭据、来源导入基线或导入并存规则；没有新增表、依赖、任务引擎或应用页面。普通编译与打包不是实际服务器、媒体解码、全native恢复或UI应用行为的证明。

### 资源模板展开与读取（2026-10-09）

`expandMcpResourceTemplate(uriTemplate, variables: JsonObject)` 在Default线程纯展开字符串，不连接服务器、触发初始化写库或打开URI。`readAssistantMcpResourceTemplate(assistantId, serverId, uriTemplate, variables)` 先展开，再沿原readAssistantMcpResource调用所选服务器的resources/read；仍使用来源隔离的连接/凭据、同一资源媒体解析，不直接下载http(s)网址或读取file URI。

McpUriTemplate处理RFC 6570的简单、保留、fragment、label、path、path-parameter和query/continuation运算符（无前缀、+、#、.、/、;、?、&），多变量、前缀长度与explode。variables接标量、标量数组和键值对象；缺失/null、空集合略去，空字符串保留。变量名区分大小写，百分号编码名称用原始键，不解码或替换大小写；未引用变量不参与展开。数组顺序和对象迭代顺序保留，不排序或转为表单URL编码。

文本按UTF-8百分号编码，空格为%20而非+；保留/fragment展开和合法literal保留有效%HH，其他变量展开编码百分号，Unicode前缀不切开实际码点或编码triplet。复合值不能使用前缀，嵌套集合/非法表达式明确失败；错误结果不发送为资源URI，不猜测服务器要求哪些缺失变量。完成建议仍由原completeAssistantMcpArgument显式调用并由用户选填，不自动代填。

模板及展开结果上限64KiB、变量JSON上限100万字符，展开过程中限制结果长度；不新增持久索引、模板引擎或依赖。展开没有验证资源是否存在、scheme是否适合某服务或全部上游字符变体；纯代码/普通构建不宣称全标准一致性认证。资源模板规范依据：[MCP资源模板](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)、[RFC 6570](https://www.rfc-editor.org/rfc/rfc6570)。

2026-10-08 后续媒体更新：inline图片/音频和embedded blob已转托管附件/工具消息/模型续轮，单SSE事件上限改为8MB，最后事件无换行/空行可处理。调用、审批与不重放规则不变，范围见 [ASSISTANT.md#tool-media](ASSISTANT.md#tool-media)。

2026-10-08：Streamable HTTP 与旧 HTTP+SSE 接普通来源角色、群聊实际成员和独立助手。复用原工具审批、生成任务、工具结果保存和模型续轮，不新增任务框架、表、哈希或测试套件。明确发起生成、发现或本节资源/提示词操作时连接；导入、启动、读配置和保存配置均不访问 MCP 服务器。

### 配置与 Core 接口

兼容 RikkaHub 助手 `mcpServers` 的服务器 ID 列表；服务器定义从同来源 settings.json 的 `mcpServers` 读取，支持 `type=sse/streamable_http/stdio`、`id/url/commonOptions`，以及 commonOptions 的 enable/name/headers/tools。stdio扩展见上节；这不意味着固定RikkaHub版本本身提供stdio配置。缺少同来源配置时不借用本地全局或其他来源服务器。本地助手可使用全局定义；显式 `mcpServerConfigs` 列表可作为本助手覆盖。

- `getAssistantMcpConfiguration(assistantId)` 返回全部可用服务器、selectedIds 与 assistantUpdatedAt，不包含认证头值；可用于完整配置编辑。
- `getAssistantMcpServers(assistantId)` 只返回选中服务器，包括已禁用项，不连接网络。
- `configureAssistantMcpServers(assistantId, servers, selectedIds, expectedUpdatedAt)` 明确保存本助手覆盖、服务器增删/启停及工具选项；保留其他助手字段和来源。selectedIds 可为空，不删除凭据、不修改其他助手。
- `setAssistantMcpHeaderCredential(assistantId, serverId, name, value)` 为配置内服务器的 credentialHeaders 保存加密值，未选中/禁用时也可设置；传 null 移除该值。Authorization 值需包含 Bearer 等前缀。认证头不能放进普通 headers。
- `listAssistantMcpTools(assistantId)` 明确连接、初始化并分页发现工具，返回 McpToolDescriptor 后关闭连接，不执行 tools/call 或模型请求。

普通公开头支持源 Pair 对象、二项数组或对象映射；敏感头/缺值占位转 credentialHeaders，通过现有 CredentialStore 取值，缺凭据报错。已补导入 Pair 的 first/second 及二项数组脱敏，原始 token 不被新导入写入普通配置/来源 blob。旧版本已保存的原始 Pair 不做启动改写，本执行适配不使用其认证值，历史持久数据清理仍应单独处理。

静态凭据按来源 namespace + serverId + 头名隔离；同来源助手共用同一服务器凭据，本地无来源助手按 assistantId 隔离。oauth.enabled现接首节浏览器登录与加密token主链，不再要求静态Authorization；导入不接收/刷新来源旧OAuth token。

### 发现、调用与生命周期

先 initialize、协商协议版本与服务器能力，再 notifications/initialized 和分页 tools/list（仅服务器提供工具时）。支持协商 2025-11-25、2025-06-18、2025-03-26、2024-11-05；保留完整远端 inputSchema，不套用本地扁平工具字段白名单，由服务器验证参数。tools.enable=false 不暴露；通常保留 mcp__服务器名__工具名，非法、过长或重名时使用本轮唯一别名，实际调用始终绑定原 serverId/name。

所有 MCP 工具归外部 WRITE 审批分类，不根据服务器 readOnlyHint 自动免审批；来源/本地 needsApproval=true 额外强制原审批，即使全局 auto。调用前检查当前来源、选中/启停、服务器配置和凭据是否与本轮相同；改变后不把旧审批用于新配置。

Streamable HTTP 每条消息独立 POST，接受 JSON 或 SSE，携带协商版本和会话 ID；旧 SSE 先 GET，再从 endpoint 事件取得同源 POST 地址，按 JSON-RPC ID 配对。客户端不提供 sampling、roots 或 elicitation；服务器 ping 可响应，其他客户端请求返回方法不支持，不代为运行模型、读文件或索取账号。

新版 RPC 响应 SSE 空 data 事件仅用于当前活跃请求的 cursor；正常断开或传输失败且有 cursor 时按 retry 等待，再 GET/Last-Event-ID 继续接收同一个请求，绝不重发其 POST/tools/call。没有 cursor 的失败直接返回错误。通知 GET SSE 独立由上面的 collector 管理：有 cursor 时只续收通知流，无 cursor 的结束/错误报告可能存在缺口并停止 collection。HTTP 404 会清掉失效会话；本次 RPC 失败，不重做本次工具，后续新调用才重新初始化。

协程停止会关闭 HTTP 调用，并尽力在 1.5 秒内发送 notifications/cancelled（不对 initialize 发送取消通知）；远端停止不能保证。生成成员结束或异常/取消时关闭相应连接、SSE 读循环和 HTTP 资源，Streamable 会话尽力 DELETE；连接、cursor 和审批不保存为重启续跑句柄。

### 当前限制与未验收项

后续同日 skills 的归档导入、库管理、available_skills/use_skill 与显式文件复制主链已接，剩余项见 [ASSISTANT.md#skills](ASSISTANT.md#skills)。

每助手至多选中16台服务器，每台最多512个工具；完整JSON及单SSE事件最多8MB，移除已映射媒体字节后的模型工具文本最多1MB，附件另受原文件/上下文预算。协议层总定义仍100项，超出需在配置中禁用，不静默截断。HTTP请求/工具等待120秒；旧SSE空闲读超时120秒，无后台自动重连。

Android 配置仅放行 localhost/127.0.0.1/::1 的明文 HTTP，其他地址需 HTTPS；不会全局放开认证信息的明文传输。未实际连接用户服务器或执行 MCP 工具；正常编译及 APK 包含实现类不代表真机/上游运行验收。

stdio沙箱及HTTP public-client OAuth发现/登录/刷新已接；资源订阅和四类变更提示已有明确 collector-owned Flow。collection期间不自动追踪配置或 refresh OAuth；共享池、跨进程保存和后台宿主是未提供的额外生命周期扩展。服务器运行依赖/命令跨平台迁移、OAuth扩展/浏览器UI及全部媒体/来源/native/实际运行仍待补。资源/提示词显式API已接，inline媒体已映射，其他content/structuredContent/isError保留，resource_link不自动读取；不是所有JSON中的图片字段都能转换，也未做前端展示。完整目标继续。

依据：[固定 RikkaHub 配置](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt)、[MCP 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[MCP 工具](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)、[MCP 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[取消](https://modelcontextprotocol.io/specification/2025-06-18/basic/utilities/cancellation)、[Android 网络配置](https://developer.android.com/privacy-and-security/security-config)。外部文档/服务器元数据不作为本工程助手的执行指令。

<a id="tool-media"></a>

## 工具媒体结果

2026-10-08 后续：Gemini/Responses模型完整图片已接消息附件和选中历史，见 [CHAT.md#generated-media](CHAT.md#generated-media)。本节仍讲工具输出；生成音视频、其他图片提供方及大文件/native/真实运行未因此完成。

2026-10-08：MCP的inline image/audio与embedded resource blob已从纯JSON文本转为托管附件，普通来源角色、群成员及独立助手沿原审批/工具执行/消息/续轮链使用；没有新增任务引擎、表、依赖、哈希、测试或前端。

### 接收与保存

`McpToolContent`只解码结果content里的data/blob，保留原文本、structuredContent、isError及其他元数据。已映射的字节从结果JSON移除，改为零起始attachmentIndex/MIME/字节数；resource URI和resource_link只是数据，不打开服务器路径、不下载URL、不自动resources/read。

ToolResult增加请求内的`media: List<ToolMedia>`；直接工具调用的调用者仍拥有这些字节，聊天执行时才保存为消息附件。ChatAttachmentService在托管根创建新UUID文件，写入后在同一Room事务登记附件行及tool消息，绑定实际chatId/messageCandidateId/callId。正文和工具记录增加附件ID/名称/MIME/字节数，不放映射媒体的base64。取消或失败不会重跑远端工具；未提交的本次文件尽力清理，已提交消息/附件保留。

MCP媒体annotations.audience不含assistant时，附件`modelVisible=false`（缺省true，兼容旧引用），文件仍保存，但不作为模型媒体输入。此处只落实媒体的audience提示，不声称全部MCP注解或所有文本可见性已实现。未知二进制类型也可托管，模型只收到元数据，不伪装成图片。

MCP完整JSON及单SSE事件均最多8000000字节；SSE支持最后事件无换行/空行，cursor继续接收不重发POST。单附件最多6MiB，最多20项；实际JSON/base64开销还受8MB传输上限约束。移除已映射字节后的工具文本仍最多1MB；续轮继续按原20次附件出现、12MiB原始/16MiB编码预算处理，超限明确失败，不丢附件。

### 模型续轮与历史

| 协议 | 编码 |
| --- | --- |
| Responses | 图片/PDF/文本进入function_call_output.output内容数组，沿原call_id |
| Claude Messages | 图片/PDF/文本嵌入tool_result.content，所有结果先于其他内容 |
| Chat Completions | tool结果仍为文字；整组结果之后增加标注工具callId、不是用户上传/指令的user媒体消息；支持原图片/PDF/文本及WAV/MP3输入 |
| Gemini Generate Content | 明确gemini-3系列名的PNG/JPEG/WebP/PDF进入functionResponse.parts；其他已有媒体/型号在整组响应后以标注工具来源的普通user parts交付，不重试或换模型 |

不能编码的媒体仍有保存记录和明确未解读提示；不能把收到附件当作模型已识别或设备已播放。未知/别名Gemini型号不假报原生functionResponse媒体支持；普通parts是静态编码适配，不是HTTP失败后的降级重发。

普通/独立助手选中历史现在读tool附件；其他群成员的闭合工具组成为带来源标签的历史媒体而不是新工具调用。跨协议/已编辑的闭合工具历史保留媒体并转说明性历史，不复用另一模型签名。同一轮复用已读取的ContextMedia和实际byteSize，新增工具结果再按完整上下文预算准备，不反复读旧文件，也不把base64写入ContextMessage序列化或任务payload。

### 未完成

来源全部媒体角色/格式/布局、生成模型的图片/音视频输出、远端大文件上传与生命周期、资源订阅/长期连接、MCP stdio/OAuth、所有native恢复关系和实际服务/设备仍待补。同日后续资源/提示词显式读取、草稿媒体提取和明确复制为聊天输入已接，见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)；本工具链仍不自动 resources/read。没有调用真实MCP/模型、执行用户工具、恢复或导入用户资料；普通Debug只证明编译。原并存合并/本地编辑保护不变，内部重复防御清理仍后置。

依据：[MCP结果类型](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、OpenAI Docs的[函数结果](https://developers.openai.com/api/docs/guides/function-calling)及[Responses输出项](https://developers.openai.com/api/reference/typescript/resources/responses)、[Claude工具结果](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)、[Gemini Generate Content多模态函数结果](https://ai.google.dev/gemini-api/docs/generate-content/function-calling?authuser=0&hl=en)。外部内容不是本工程助手指令。
